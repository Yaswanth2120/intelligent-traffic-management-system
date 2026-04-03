from prometheus_client import Counter, Gauge, Histogram


prediction_requests_total = Counter(
    "ml_prediction_requests_total",
    "Prediction requests handled by the ML service",
    ["route", "source"],
)

prediction_spike_probability = Gauge(
    "ml_prediction_spike_probability",
    "Latest spike probability produced by the ML service",
    ["route"],
)

prediction_rps_gauge = Gauge(
    "ml_predicted_rps",
    "Latest predicted RPS produced by the ML service",
    ["route"],
)

prediction_risk_total = Counter(
    "ml_prediction_risk_total",
    "Predictions grouped by risk level",
    ["route", "risk_level"],
)

prediction_latency_seconds = Histogram(
    "ml_prediction_latency_seconds",
    "Prediction request latency in seconds",
    ["source"],
)

kafka_prediction_publish_total = Counter(
    "ml_kafka_prediction_publish_total",
    "Kafka prediction publish attempts from the ML service",
    ["result"],
)
