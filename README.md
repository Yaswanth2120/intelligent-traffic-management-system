# Intelligent Traffic Management System

A cloud-native distributed platform that predicts API traffic, detects spikes, and dynamically adjusts traffic policies to protect backend services, reduce infrastructure waste, and maintain low latency.

The system combines API gateway telemetry, Kafka event streaming, rolling feature aggregation, machine learning inference, rule-based traffic decisions, and observability dashboards.

## What This Project Does

Modern SaaS systems often receive unpredictable traffic from product launches, campaigns, seasonal load, bots, and abusive clients. Static rate limits and fixed infrastructure sizing can either waste money or allow outages.

This project continuously analyzes live API traffic and automatically produces gateway policies such as throttling, load shedding, and route protection.

## Key Features

- API gateway request routing with Spring Cloud Gateway.
- Real-time traffic metric publishing to Kafka.
- Rolling traffic feature aggregation by route.
- Redis storage for hot recent traffic windows.
- PostgreSQL storage for historical traffic and policy decisions.
- FastAPI ML service for traffic prediction and spike scoring.
- Kafka-based ML prediction pipeline.
- Decision engine for generating dynamic traffic policies.
- Gateway policy enforcement with route-level rate limits.
- Prometheus metrics across all services.
- Grafana dashboard provisioning.
- Docker Compose local infrastructure.
- Kubernetes manifests with deployments, services, config, resource limits, and HPAs.
- GitHub Actions validation workflows.
- GHCR container build and publish workflow.
- Load testing scenarios with k6.
- Chaos testing scripts for Kafka and Redis outage drills.
- Model registry and model lifecycle documentation.
- End-to-end local smoke test harness.

## Architecture

```text
Client Requests
      |
      v
Spring Cloud Gateway
      |
      v
Kafka: traffic_metrics
      |
      v
Feature Aggregation Service
      |              |
      v              v
Redis          PostgreSQL
      |
      v
Kafka: aggregated_features
      |
      v
FastAPI ML Prediction Service
      |
      v
Kafka: ml_predictions
      |
      v
Decision Engine
      |
      v
Kafka: traffic_decisions
      |
      v
Gateway Dynamic Policies
```

Observability:

```text
Services -> Prometheus -> Grafana
```

## Services

### Gateway Service

Spring Cloud Gateway entry point for API traffic.

Responsibilities:

- Routes client requests.
- Captures request metrics.
- Publishes traffic events to Kafka.
- Consumes traffic decisions.
- Enforces route-level dynamic rate limits.
- Exposes health and Prometheus metrics.

Example route:

```text
GET /api/orders
```

### Feature Service

Spring Boot service that consumes raw traffic metrics and builds rolling windows.

Responsibilities:

- Consumes `traffic_metrics`.
- Calculates requests per second, latency, error rate, and unique clients.
- Stores hot windows in Redis.
- Stores historical windows in PostgreSQL.
- Publishes `aggregated_features`.

### ML Service

FastAPI service for traffic prediction.

Responsibilities:

- Accepts direct prediction requests.
- Optionally consumes `aggregated_features`.
- Predicts future RPS.
- Calculates spike probability.
- Produces risk level.
- Publishes `ml_predictions`.
- Exposes `/health`, `/predict`, `/predict/aggregate`, and `/metrics`.

### Decision Engine

Spring Boot service that converts ML predictions into gateway policies.

Responsibilities:

- Consumes `ml_predictions`.
- Applies policy rules.
- Generates throttle and rate-limit decisions.
- Stores policy decisions in PostgreSQL.
- Publishes `traffic_decisions`.

## Kafka Topics

```text
traffic_metrics
aggregated_features
ml_predictions
traffic_decisions
```

## Tech Stack

### Backend

- Java 17
- Spring Boot
- Spring Cloud Gateway
- Spring Kafka
- Redis
- PostgreSQL

### Machine Learning

- Python
- FastAPI
- Baseline traffic prediction logic
- Pydantic
- aiokafka
- Prometheus instrumentation

### Infrastructure

- Docker
- Docker Compose
- Kubernetes
- GitHub Actions
- GitHub Container Registry

### Observability

- Prometheus
- Grafana
- Micrometer
- k6

## Repository Structure

```text
gateway-service/        Spring Cloud Gateway service
feature-service/        Traffic aggregation service
decision-engine/        Traffic policy decision engine
ml-service/             FastAPI ML prediction service
contracts/schemas/      JSON event contracts
infra/docker/           Local Docker Compose infrastructure
infra/k8s/              Kubernetes manifests
tests/e2e/              End-to-end smoke test harness
tests/load/             k6 load testing scenarios
tests/chaos/            Chaos testing scripts and runbooks
docs/                   Architecture, phases, model lifecycle notes
.github/workflows/      CI/CD workflows
```

## Running Locally

### Prerequisites

- Docker Desktop
- Java 17+
- Maven
- Python 3.13 recommended for the ML service
- GitHub CLI optional

### Start Infrastructure

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

This starts Kafka-compatible messaging, Redis, PostgreSQL, Prometheus, and Grafana.

### Build Java Services

```bash
mvn -q -DskipTests package
```

### Run Services Manually

Gateway:

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
java -jar gateway-service/target/gateway-service-*.jar
```

Feature service:

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
POSTGRES_URL=jdbc:postgresql://localhost:5432/traffic_platform \
REDIS_PORT=6379 \
java -jar feature-service/target/feature-service-*.jar
```

Decision engine:

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
POSTGRES_URL=jdbc:postgresql://localhost:5432/traffic_platform \
java -jar decision-engine/target/decision-engine-*.jar
```

ML service:

```bash
python3.13 -m venv .venv
source .venv/bin/activate
pip install -r ml-service/requirements.txt
uvicorn app.main:app --app-dir ml-service --host 0.0.0.0 --port 8000
```

## Useful URLs

```text
Gateway health:      http://localhost:8080/actuator/health
Feature health:      http://localhost:8081/actuator/health
Decision health:     http://localhost:8082/actuator/health
ML health:           http://localhost:8000/health
ML API docs:         http://localhost:8000/docs
Prometheus:          http://localhost:9090
Grafana:             http://localhost:3000
Gateway demo route:  http://localhost:8080/api/orders
```

Grafana default credentials:

```text
username: admin
password: admin
```

## Testing

### Java Tests

```bash
mvn -q -pl gateway-service,feature-service,decision-engine test
```

### Java Package Build

```bash
mvn -q -DskipTests package
```

### ML Tests

```bash
python3.13 -m venv /tmp/traffic-platform-venv
/tmp/traffic-platform-venv/bin/pip install -r ml-service/requirements.txt
PYTHONPATH=ml-service /tmp/traffic-platform-venv/bin/pytest -q ml-service/test_predictor.py
```

### Model Registry Validation

```bash
python3 ml-service/scripts/validate_model_registry.py
```

### End-to-End Smoke Test

```bash
tests/e2e/run-local-e2e.sh
```

The E2E script starts isolated local infrastructure, builds services, starts all services, sends sample gateway traffic, checks ML prediction, and verifies Prometheus metrics.

## CI/CD

GitHub Actions workflows include:

- Basic project validation.
- Java service tests.
- ML tests.
- Model registry validation.
- Docker Compose and Kubernetes asset validation.
- Container build and publish to GitHub Container Registry.

The container publish workflow builds images for:

```text
gateway-service
feature-service
decision-engine
ml-service
```

## Kubernetes Support

Kubernetes manifests are included for:

- Namespace
- Shared configuration
- Gateway deployment and service
- Feature service deployment and service
- Decision engine deployment and service
- ML service deployment and service
- Resource requests and limits
- Horizontal Pod Autoscalers

Files are located in:

```text
infra/k8s/
```

## Load Testing

k6 scenarios are included:

```text
tests/load/k6/steady-state.js
tests/load/k6/spike-traffic.js
```

These simulate normal traffic and spike traffic against the gateway route.

## Chaos Testing

Chaos scripts are included for outage drills:

```text
tests/chaos/scripts/simulate-kafka-outage.sh
tests/chaos/scripts/restore-kafka.sh
tests/chaos/scripts/simulate-redis-outage.sh
tests/chaos/scripts/restore-redis.sh
```

These help validate graceful degradation when infrastructure dependencies fail.

## Model Lifecycle

The ML model registry is defined in:

```text
ml-service/model-registry.json
```

Model lifecycle documentation is in:

```text
ml-service/model_lifecycle.md
```

The current baseline model produces:

- Predicted RPS
- Spike probability
- Risk level
- Prediction horizon

## What Was Built

This project was completed in multiple phases:

- Phase 1: project foundation, schemas, service skeletons, local infrastructure.
- Phase 2: gateway telemetry and feature aggregation pipeline.
- Phase 3: ML prediction service, Kafka ML pipeline, decision engine, gateway policy loop.
- Phase 4: Prometheus metrics and Grafana dashboards.
- Phase 5: validation workflow, integration docs, load tests, chaos scripts.
- Phase 6: Kubernetes manifests, resource policies, autoscaling.
- Phase 7: Dockerfiles, GHCR publishing, model registry, lifecycle validation, E2E harness.

## Current Status

Completed:

- Core distributed traffic management platform.
- Local Docker infrastructure.
- Java and Python services.
- Kafka event pipeline.
- ML prediction and decision loop.
- Observability dashboard assets.
- CI/CD validation.
- Container publishing workflow.
- Kubernetes deployment assets.
- Load and chaos testing assets.

Latest GitHub checks are passing after fixing the Java Docker image builds.

## Future Improvements

- Replace baseline ML logic with Prophet, LSTM, or online forecasting.
- Add real autoscaler integration with Kubernetes metrics.
- Add multi-region routing support.
- Add authentication and tenant-aware traffic policies.
- Add persistent model artifacts and automated retraining pipeline.
- Add a frontend dashboard for traffic decisions and prediction review.
- Add Testcontainers-based integration tests for Kafka, Redis, and PostgreSQL.

## Resume Value

This project demonstrates:

- Distributed systems design.
- Event-driven microservices.
- API gateway traffic control.
- Kafka streaming.
- ML inference integration.
- Cloud-native infrastructure.
- Observability with Prometheus and Grafana.
- Docker and Kubernetes deployment.
- CI/CD and container publishing.
- Load and chaos testing.
