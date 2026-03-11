# Delivery Roadmap

## Phase 1: Foundation

- Create local environment with Kafka, Redis, PostgreSQL, Prometheus, and Grafana.
- Define event schemas for metrics, features, predictions, and decisions.
- Establish service contracts and health checks.

Exit criteria:

- All core infra boots locally.
- Topics and databases are reachable.

## Phase 2: Gateway Telemetry

- Build Spring Cloud Gateway project.
- Publish non-blocking traffic metrics to Kafka.
- Add route-level metric tags and Prometheus exposure.

Exit criteria:

- Requests produce valid `traffic_metrics` events.

## Phase 3: Feature Aggregation

- Build feature-service consumer.
- Compute rolling RPS, error rate, and latency windows.
- Persist hot state in Redis and historical data in PostgreSQL.

Exit criteria:

- Aggregated feature vectors are queryable and durable.

## Phase 4: ML Inference

- Build FastAPI prediction service.
- Start with a simple baseline model before LSTM.
- Publish predictions and track model version plus confidence.

Exit criteria:

- Service returns stable predictions from live features.

## Phase 5: Decisioning

- Build Spring Boot decision engine.
- Apply deterministic safety rules first.
- Add dynamic policy publishing and audit logging.

Exit criteria:

- Gateway receives and enforces live rate-limit decisions.

## Phase 6: Validation

- Add integration tests with Testcontainers.
- Add load tests with k6 for baseline and spike scenarios.
- Validate graceful degradation for Kafka, Redis, and ML outages.

Exit criteria:

- Control loop remains stable under spike and partial failure conditions.

## Phase 7: Production Hardening

- Add Kubernetes manifests and autoscaling.
- Add retraining pipeline and model promotion flow.
- Add cost and accuracy dashboards.

Exit criteria:

- Deployment is production-ready with operational runbooks.

## Recommended MVP Cuts

- Use Prophet or gradient boosting before LSTM.
- Support only rate limiting in the first release.
- Keep routing failover rules static until telemetry quality is proven.
