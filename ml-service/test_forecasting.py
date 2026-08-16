import math

import pytest

from app.dataset import SEASONAL_PERIOD_HOURS, chronological_split, generate_synthetic_series
from app.forecasting import HoltWintersForecaster, InsufficientHistoryError, mae, mape, rmse


def test_dataset_generation_is_deterministic() -> None:
    a = generate_synthetic_series(days=10, seed=7)
    b = generate_synthetic_series(days=10, seed=7)

    assert [p.requests_per_sec for p in a] == [p.requests_per_sec for p in b]


def test_dataset_generation_differs_by_seed() -> None:
    a = generate_synthetic_series(days=10, seed=1)
    b = generate_synthetic_series(days=10, seed=2)

    assert [p.requests_per_sec for p in a] != [p.requests_per_sec for p in b]


def test_chronological_split_does_not_shuffle() -> None:
    points = generate_synthetic_series(days=10, seed=42)
    train, test = chronological_split(points, test_fraction=0.2)

    assert [p.hour_index for p in train] == sorted(p.hour_index for p in train)
    assert [p.hour_index for p in test] == sorted(p.hour_index for p in test)
    assert train[-1].hour_index < test[0].hour_index
    assert len(train) + len(test) == len(points)


def test_forecaster_requires_two_full_seasons() -> None:
    forecaster = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIOD_HOURS)

    with pytest.raises(InsufficientHistoryError):
        forecaster.fit([100.0] * (2 * SEASONAL_PERIOD_HOURS - 1))


def test_forecaster_forecast_before_fit_raises() -> None:
    forecaster = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIOD_HOURS)

    with pytest.raises(InsufficientHistoryError):
        forecaster.forecast(steps=1)


def test_forecaster_is_deterministic() -> None:
    points = generate_synthetic_series(days=20, seed=42)
    series = [p.requests_per_sec for p in points]

    a = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIOD_HOURS).fit(series).forecast(steps=5)
    b = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIOD_HOURS).fit(series).forecast(steps=5)

    assert a == b


def test_forecaster_tracks_flat_series() -> None:
    series = [150.0] * (4 * SEASONAL_PERIOD_HOURS)

    forecaster = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIOD_HOURS).fit(series)
    forecast = forecaster.forecast(steps=SEASONAL_PERIOD_HOURS)

    for value in forecast:
        assert math.isclose(value, 150.0, rel_tol=0.05)


def test_forecaster_never_predicts_negative_rps() -> None:
    series = [1.0] * (2 * SEASONAL_PERIOD_HOURS) + [0.0] * (2 * SEASONAL_PERIOD_HOURS)

    forecaster = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIOD_HOURS).fit(series)
    forecast = forecaster.forecast(steps=SEASONAL_PERIOD_HOURS)

    assert all(value >= 0.0 for value in forecast)


def test_metrics_reject_mismatched_lengths() -> None:
    with pytest.raises(ValueError):
        mae([1.0, 2.0], [1.0])
    with pytest.raises(ValueError):
        rmse([1.0, 2.0], [1.0])
    with pytest.raises(ValueError):
        mape([1.0, 2.0], [1.0])


def test_mape_is_safe_against_near_zero_actuals() -> None:
    # Without an epsilon floor this would divide by ~0 and blow up.
    value = mape([0.0001, 10.0], [1.0, 11.0], epsilon=1.0)
    assert math.isfinite(value)
    assert value >= 0.0


def test_metrics_are_zero_for_perfect_predictions() -> None:
    actual = [10.0, 20.0, 30.0]
    assert mae(actual, actual) == 0.0
    assert rmse(actual, actual) == 0.0
    assert mape(actual, actual) == 0.0
