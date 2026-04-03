from .models import AggregatedFeaturesRequest, PredictionRequest, PredictionResponse
from .metrics import prediction_risk_total, prediction_rps_gauge, prediction_spike_probability


def predict_from_aggregate(request: AggregatedFeaturesRequest) -> PredictionResponse:
    latency_factor = min(request.p95_latency_ms / 300.0, 1.0)
    client_factor = min(request.unique_clients / 100.0, 1.0)
    error_factor = request.error_rate

    predicted_rps = round(
        request.requests_per_sec
        * (1.08 + (latency_factor * 0.14) + (client_factor * 0.10) + (error_factor * 0.25)),
        2,
    )

    spike_probability = min(
        0.99,
        round(0.18 + (error_factor * 0.35) + (latency_factor * 0.27) + (client_factor * 0.20), 2),
    )

    if spike_probability >= 0.80 or predicted_rps >= request.requests_per_sec * 1.35:
        risk_level = "high"
    elif spike_probability >= 0.50 or predicted_rps >= request.requests_per_sec * 1.15:
        risk_level = "medium"
    else:
        risk_level = "low"

    response = PredictionResponse(
        route=request.route,
        model_version="baseline-v2-aggregate",
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
