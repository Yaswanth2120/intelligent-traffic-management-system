# Integration Validation

This folder documents the end-to-end validation flow for the traffic platform.

## Covered Flow

1. Gateway emits `traffic_metrics`
2. Feature service consumes metrics and publishes `aggregated_features`
3. ML service converts aggregates into `ml_predictions`
4. Decision engine converts predictions into `traffic_decisions`
5. Gateway enforces active rate limits

## Validation Layers

- Java module tests:
  - gateway-service
  - feature-service
  - decision-engine
- ML service smoke test:
  - predictor logic
  - Python module compilation
- Asset validation:
  - Grafana dashboard JSON
  - Docker Compose YAML
  - Kubernetes YAML

## Future Expansion

- Add Testcontainers-backed Kafka and PostgreSQL integration tests
- Add an end-to-end replay harness for event payloads
