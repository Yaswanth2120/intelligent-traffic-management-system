from collections import defaultdict, deque

from .forecasting import HoltWintersForecaster, InsufficientHistoryError
from .metrics import (
    forecast_model_selection_total,
    prediction_risk_total,
    prediction_rps_gauge,
    prediction_spike_probability,
)
from .models import AggregatedFeaturesRequest, PredictionRequest, PredictionResponse

# Seasonal period the Holt-Winters model is fit against: one day of hourly
# windows. See docs/forecasting.md for why this granularity was chosen.
SEASONAL_PERIODS = 24
MIN_HISTORY_LENGTH = 2 * SEASONAL_PERIODS

# Per-route in-memory history buffer used to fit the forecaster. Bounded to
# the 7-day "max age of historical buffer" objective in docs/slo.md. This is
# process-local state -- see docs/forecasting.md limitations.
_HISTORY_MAXLEN = SEASONAL_PERIODS * 7
_route_history: dict[str, deque[float]] = defaultdict(lambda: deque(maxlen=_HISTORY_MAXLEN))

HOLT_WINTERS_MODEL_VERSION = "holt-winters-v1"
HEURISTIC_MODEL_VERSION = "baseline-v2-aggregate"


def _operational_factors(request: AggregatedFeaturesRequest) -> tuple[float, float, float]:
    latency_factor = min(request.p95_latency_ms / 300.0, 1.0)
    client_factor = min(request.unique_clients / 100.0, 1.0)
    error_factor = request.error_rate
    return latency_factor, client_factor, error_factor


def _heuristic_predicted_rps(
    request: AggregatedFeaturesRequest, latency_factor: float, client_factor: float, error_factor: float
) -> float:
    """The original average/multiplier-based baseline. Kept as the fallback
    path for routes without enough history yet, and as the benchmark model
    in docs/forecasting.md."""
    return round(
        request.requests_per_sec
        * (1.08 + (latency_factor * 0.14) + (client_factor * 0.10) + (error_factor * 0.25)),
        2,
    )


def _forecast_predicted_rps(route: str) -> float | None:
    """Fit the Holt-Winters model on the route's history buffer (which
    already includes the current observation) and forecast one window ahead.
    Returns None if there isn't enough history yet."""
    history = _route_history[route]
    if len(history) < MIN_HISTORY_LENGTH:
        return None
    try:
        forecaster = HoltWintersForecaster(seasonal_periods=SEASONAL_PERIODS)
        forecaster.fit(list(history))
        return round(forecaster.forecast(steps=1)[0], 2)
    except InsufficientHistoryError:
        return None


def predict_from_aggregate(request: AggregatedFeaturesRequest) -> PredictionResponse:
    latency_factor, client_factor, error_factor = _operational_factors(request)

    _route_history[request.route].append(request.requests_per_sec)

    forecast_value = _forecast_predicted_rps(request.route)
    if forecast_value is not None:
        predicted_rps = forecast_value
        model_version = HOLT_WINTERS_MODEL_VERSION
    else:
        predicted_rps = _heuristic_predicted_rps(request, latency_factor, client_factor, error_factor)
        model_version = HEURISTIC_MODEL_VERSION
    forecast_model_selection_total.labels(route=request.route, model=model_version).inc()

    relative_growth = max(0.0, predicted_rps - request.requests_per_sec) / max(request.requests_per_sec, 1.0)
    spike_probability = min(
        0.99,
        round(
            0.18
            + (error_factor * 0.35)
            + (latency_factor * 0.27)
            + (client_factor * 0.20)
            + min(relative_growth, 1.0) * 0.15,
            2,
        ),
    )

    if spike_probability >= 0.80 or predicted_rps >= request.requests_per_sec * 1.35:
        risk_level = "high"
    elif spike_probability >= 0.50 or predicted_rps >= request.requests_per_sec * 1.15:
        risk_level = "medium"
    else:
        risk_level = "low"

    response = PredictionResponse(
        route=request.route,
        model_version=model_version,
        predicted_rps=predicted_rps,
        spike_probability=spike_probability,
        risk_level=risk_level,
        prediction_horizon_sec=request.window_size_sec,
    )
    prediction_rps_gauge.labels(route=response.route).set(response.predicted_rps)
    prediction_spike_probability.labels(route=response.route).set(response.spike_probability)
    prediction_risk_total.labels(route=response.route, risk_level=response.risk_level).inc()
    return response


def predict_from_legacy(request: PredictionRequest) -> PredictionResponse:
    aggregate_request = AggregatedFeaturesRequest(
        route=request.route,
        window_start=0,
        window_size_sec=60,
        requests_per_sec=request.recent_rps,
        error_rate=request.error_rate,
        avg_latency_ms=max(50.0, request.recent_rps * 0.4),
        p95_latency_ms=max(90.0, request.recent_rps * 0.65),
        unique_clients=max(1, int(request.recent_rps // 4)),
    )
    return predict_from_aggregate(aggregate_request)
