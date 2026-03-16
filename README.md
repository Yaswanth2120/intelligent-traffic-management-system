# Intelligent Traffic Shaping & Cost Optimization Platform

Distributed platform for predicting API traffic, preventing overload, and reducing infrastructure cost through dynamic gateway policies.

## What This System Does

- Collects live API traffic metrics from a gateway.
- Aggregates short-window features in Redis and historical features in PostgreSQL.
- Predicts near-future load and spike probability with an ML service.
- Produces throttling, routing, and scaling decisions in real time.
- Exposes observability for traffic health, model quality, and cost efficiency.

## Core Services

- `gateway-service`: Spring Cloud Gateway, request metering, dynamic policy enforcement.
- `feature-service`: Kafka consumer, window aggregation, Redis/PostgreSQL persistence.
- `ml-service`: FastAPI inference service for traffic prediction and anomaly scoring.
- `decision-engine`: Spring Boot policy engine consuming predictions and publishing decisions.
- `infra/docker`: local development stack.
- `infra/k8s`: deployment manifests placeholder.
- `docs`: architecture, delivery plan, and implementation notes.

## Event Flow

1. Client requests enter the API gateway.
2. Gateway publishes request metrics to Kafka topic `traffic_metrics`.
3. Feature service builds rolling traffic windows and stores:
   - hot state in Redis
   - history in PostgreSQL
4. ML service reads current features and outputs:
   - `predicted_rps`
   - `spike_probability`
   - `risk_level`
5. Decision engine publishes traffic policies to `traffic_decisions`.
6. Gateway updates route policies without restart.

## Initial Kafka Topics

- `traffic_metrics`
- `aggregated_features`
- `ml_predictions`
- `traffic_decisions`

## Recommended MVP Scope

- One protected route, for example `/api/orders`
- One traffic control action: token-bucket rate limiting
- One model output: 1-minute-ahead `predicted_rps`
- One fallback rule: shed traffic when latency or error rate crosses threshold

## Success Metrics

- P95 latency remains within SLA during spikes
- Gateway prevents backend saturation
- False-positive throttling remains low
- Infrastructure runs closer to actual demand

## Suggested Build Order

1. Stand up local infra with Kafka, Redis, PostgreSQL, Prometheus, and Grafana.
2. Implement gateway metrics publishing.
3. Implement feature aggregation windows.
4. Expose prediction endpoint in the ML service.
5. Implement decision rules and gateway policy updates.
6. Add dashboards, load tests, and model retraining pipeline.

## Phase 1 Deliverables

- Contract-first event schemas in `contracts/schemas`
- Local infrastructure in `infra/docker`
- Minimal service skeletons with health endpoints
- Baseline CI workflow for structure and Python syntax checks

### Service Health Endpoints

- Gateway: `GET /actuator/health`
- Feature Service: `GET /actuator/health`
- Decision Engine: `GET /actuator/health`
- ML Service: `GET /health`

## Next Step

Use [docs/architecture.md](/Users/yaswanth/Desktop/Project1/docs/architecture.md) for the system contract and [docs/roadmap.md](/Users/yaswanth/Desktop/Project1/docs/roadmap.md) for execution phases.

## Phase 2 Breakdown

- Part 1: gateway telemetry foundation
  - add a real `/api/orders` route
  - capture request metadata and latency
  - publish `traffic_metrics` events to Kafka asynchronously
- Part 2: feature aggregation pipeline
  - consume `traffic_metrics` in `feature-service`
  - compute rolling traffic windows
  - persist hot windows to Redis and history to PostgreSQL
