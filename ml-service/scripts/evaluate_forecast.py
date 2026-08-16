"""Offline evaluation: Holt-Winters forecaster vs. the existing production baseline.

Usage:
    python scripts/evaluate_forecast.py            # human-readable report
    python scripts/evaluate_forecast.py --json      # machine-readable report

Methodology (see docs/forecasting.md for full detail):
  1. Generate a deterministic synthetic hourly traffic series (app/dataset.py).
  2. Split it chronologically into train/test (no shuffling).
  3. Baseline: replay the *existing* `predict_from_aggregate` heuristic
     (app/predictor.py) over the test set, one step at a time, and compare its
     `predicted_rps` against the next hour's actual value.
  4. Candidate: fit `HoltWintersForecaster` on the train set, then walk forward
     through the test set doing 1-step-ahead forecasts, extending the fitted
     model's history with each newly observed actual as it "arrives" (the
     model is not refit each step -- it reuses its fitted level/trend/seasonal
     state and folds in new observations, which is what the live in-memory
     history buffer in app/predictor.py does).
  5. Report MAE / RMSE / MAPE for both.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.dataset import SEASONAL_PERIOD_HOURS, chronological_split, generate_synthetic_series
from app.forecasting import HoltWintersForecaster, mae, mape, rmse
from app.models import AggregatedFeaturesRequest
from app.predictor import _heuristic_predicted_rps, _operational_factors


def _baseline_predictions(test_points) -> list[float]:
    """Replay the existing production heuristic, one step at a time.

    Calls the pure heuristic function directly (not `predict_from_aggregate`)
    so this measurement isn't contaminated by that function's *stateful*
    per-route history buffer, which would otherwise start routing these same
    calls to the new Holt-Winters model after `MIN_HISTORY_LENGTH` points and
    defeat the point of a baseline comparison.
    """
    predictions = []
    for point in test_points:
        request = AggregatedFeaturesRequest(
            route=point.route,
            window_start=point.hour_index * 3600,
            window_size_sec=3600,
            requests_per_sec=point.requests_per_sec,
            error_rate=point.error_rate,
            avg_latency_ms=point.avg_latency_ms,
            p95_latency_ms=point.p95_latency_ms,
            unique_clients=point.unique_clients,
        )
        latency_factor, client_factor, error_factor = _operational_factors(request)
        predictions.append(
            _heuristic_predicted_rps(request, latency_factor, client_factor, error_factor)
        )
    return predictions


def _holt_winters_predictions(train_series: list[float], test_series: list[float]) -> list[float]:
    forecaster = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIOD_HOURS)
    history = list(train_series)
    predictions = []
    for actual in test_series:
        forecaster.fit(history)
        predictions.append(forecaster.forecast(steps=1)[0])
        history.append(actual)
    return predictions


def run_evaluation(days: int = 60, test_fraction: float = 0.2, seed: int = 42) -> dict:
    points = generate_synthetic_series(days=days, seed=seed)
    train_points, test_points = chronological_split(points, test_fraction=test_fraction)

    train_series = [p.requests_per_sec for p in train_points]
    test_series = [p.requests_per_sec for p in test_points]

    # Baseline predicts predicted_rps "now" for the *next* window, so align
    # its prediction for point[i] against the actual value at point[i+1].
    # The final test point has no known next-actual, so it's excluded from
    # baseline scoring but still used to seed history for the Holt-Winters walk.
    baseline_all = _baseline_predictions(test_points)
    baseline_predictions = baseline_all[:-1]
    baseline_actuals = test_series[1:]

    hw_predictions_all = _holt_winters_predictions(train_series, test_series)
    hw_predictions = hw_predictions_all[:-1]
    hw_actuals = test_series[1:]

    return {
        "dataset": {
            "days": days,
            "total_points": len(points),
            "train_points": len(train_points),
            "test_points": len(test_points),
            "seed": seed,
        },
        "existing_baseline": {
            "model_version": "baseline-v2-aggregate",
            "mae": round(mae(baseline_actuals, baseline_predictions), 4),
            "rmse": round(rmse(baseline_actuals, baseline_predictions), 4),
            "mape": round(mape(baseline_actuals, baseline_predictions), 4),
        },
        "holt_winters": {
            "model_version": "holt-winters-v1",
            "mae": round(mae(hw_actuals, hw_predictions), 4),
            "rmse": round(rmse(hw_actuals, hw_predictions), 4),
            "mape": round(mape(hw_actuals, hw_predictions), 4),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--days", type=int, default=60)
    parser.add_argument("--test-fraction", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    args = parser.parse_args()

    results = run_evaluation(days=args.days, test_fraction=args.test_fraction, seed=args.seed)

    if args.json:
        print(json.dumps(results, indent=2))
        return

    ds = results["dataset"]
    print(f"Dataset: {ds['total_points']} hourly points ({ds['days']} days), "
          f"seed={ds['seed']}, train={ds['train_points']}, test={ds['test_points']}")
    print()
    print(f"{'Model':<25}{'MAE':>10}{'RMSE':>10}{'MAPE (%)':>12}")
    for key in ("existing_baseline", "holt_winters"):
        r = results[key]
        print(f"{r['model_version']:<25}{r['mae']:>10}{r['rmse']:>10}{r['mape']:>12}")


if __name__ == "__main__":
    main()
