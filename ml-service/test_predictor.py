import math

from app.models import AggregatedFeaturesRequest, PredictionRequest
from app.predictor import (
    HEURISTIC_MODEL_VERSION,
    HOLT_WINTERS_MODEL_VERSION,
    MIN_HISTORY_LENGTH,
    _route_history,
    predict_from_aggregate,
    predict_from_legacy,
)


def _aggregate_request(route: str, rps: float, **overrides) -> AggregatedFeaturesRequest:
    fields = dict(
        route=route,
        window_start=1712600960,
        window_size_sec=60,
        requests_per_sec=rps,
        error_rate=0.01,
        avg_latency_ms=80.0,
        p95_latency_ms=150.0,
        unique_clients=40,
    )
    fields.update(overrides)
    return AggregatedFeaturesRequest(**fields)


def test_predict_from_aggregate_high_risk() -> None:
    request = AggregatedFeaturesRequest(
        route="orders-route-high-risk",
        window_start=1712600960,
        window_size_sec=60,
        requests_per_sec=340.0,
        error_rate=0.12,
        avg_latency_ms=180.0,
        p95_latency_ms=420.0,
        unique_clients=140,
    )

    result = predict_from_aggregate(request)

    assert result.route == "orders-route-high-risk"
    assert result.predicted_rps > request.requests_per_sec
    assert result.spike_probability >= 0.5
    assert result.risk_level in {"medium", "high"}


def test_new_route_falls_back_to_heuristic_baseline() -> None:
    route = "route-with-no-history-yet"
    _route_history.pop(route, None)

    result = predict_from_aggregate(_aggregate_request(route, 100.0))

    assert result.model_version == HEURISTIC_MODEL_VERSION


def test_route_switches_to_forecast_model_once_history_is_sufficient() -> None:
    route = "route-that-accumulates-history"
    _route_history.pop(route, None)

    last_result = None
    for i in range(MIN_HISTORY_LENGTH + 5):
        rps = 100.0 + 10.0 * math.sin(i)
        last_result = predict_from_aggregate(_aggregate_request(route, rps))

    assert last_result is not None
    assert last_result.model_version == HOLT_WINTERS_MODEL_VERSION
    assert len(_route_history[route]) == MIN_HISTORY_LENGTH + 5


def test_prediction_never_negative_even_with_declining_history() -> None:
    route = "route-declining-to-zero"
    _route_history.pop(route, None)

    last_result = None
    for i in range(MIN_HISTORY_LENGTH + 3):
        rps = max(0.0, 50.0 - i)
        last_result = predict_from_aggregate(_aggregate_request(route, rps))

    assert last_result is not None
    assert last_result.predicted_rps >= 0.0


def test_predict_from_legacy_still_returns_valid_response() -> None:
    request = PredictionRequest(
        route="legacy-route",
        time_of_day=14,
        day_of_week=2,
        recent_rps=120.0,
        error_rate=0.02,
    )

    result = predict_from_legacy(request)

    assert result.route == "legacy-route"
    assert result.predicted_rps > 0
    assert result.risk_level in {"low", "medium", "high"}


def test_history_buffer_is_bounded_and_per_route() -> None:
    route_a = "route-a-isolated"
    route_b = "route-b-isolated"
    _route_history.pop(route_a, None)
    _route_history.pop(route_b, None)

    for _ in range(5):
        predict_from_aggregate(_aggregate_request(route_a, 100.0))
    predict_from_aggregate(_aggregate_request(route_b, 500.0))

    assert len(_route_history[route_a]) == 5
    assert len(_route_history[route_b]) == 1
    assert list(_route_history[route_b]) == [500.0]
