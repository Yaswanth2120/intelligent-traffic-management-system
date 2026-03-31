from app.models import AggregatedFeaturesRequest
from app.predictor import predict_from_aggregate


def test_predict_from_aggregate_high_risk() -> None:
    request = AggregatedFeaturesRequest(
        route="orders-route",
        window_start=1712600960,
        window_size_sec=60,
        requests_per_sec=340.0,
        error_rate=0.12,
        avg_latency_ms=180.0,
        p95_latency_ms=420.0,
        unique_clients=140,
    )

    result = predict_from_aggregate(request)

    assert result.route == "orders-route"
    assert result.predicted_rps > request.requests_per_sec
    assert result.spike_probability >= 0.5
    assert result.risk_level in {"medium", "high"}
