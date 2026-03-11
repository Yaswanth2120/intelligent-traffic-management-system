# Architecture

## Problem Statement

Static rate limits and manual scaling fail under bursty SaaS traffic. The platform should predict short-term load, detect anomalies, and apply traffic controls before backend saturation occurs.

## Functional Requirements

- Ingest request metrics in near real time.
- Compute per-route rolling features.
- Predict traffic one or more windows ahead.
- Detect abnormal spikes and abusive patterns.
- Push rate-limit, routing, and load-shedding policies dynamically.
- Provide operator visibility into system behavior and model performance.

## Non-Functional Requirements

- End-to-end control loop under 5 seconds for hot routes.
- Horizontal scalability for gateway and consumers.
- Graceful degradation if ML service is unavailable.
- At-least-once event processing with idempotent policy application.

## Logical Components

### 1. Gateway

Responsibilities:

- Route traffic to backend services.
- Emit request metrics asynchronously.
- Apply current route policy from in-memory cache.
- Fall back to static safe defaults when control plane is unavailable.

Metric event contract:

```json
{
  "route": "/api/orders",
  "timestamp": 1712601020,
  "method": "GET",
  "status": 200,
  "latency_ms": 120,
  "client_id": "public-api",
  "client_ip": "10.0.0.8"
}
```

### 2. Feature Service

Responsibilities:

- Consume `traffic_metrics`.
- Compute rolling windows per route and per service.
- Publish feature vectors to `aggregated_features`.
- Persist hot windows in Redis and training history in PostgreSQL.

Feature contract:

```json
{
  "route": "/api/orders",
  "window_start": 1712600960,
  "window_size_sec": 60,
  "requests_per_sec": 340.0,
  "error_rate": 0.012,
  "avg_latency_ms": 110.0,
  "p95_latency_ms": 190.0,
  "unique_clients": 82
}
```

### 3. ML Service

Responsibilities:

- Accept feature vectors.
- Predict short-horizon traffic.
- Score spike likelihood and risk level.
- Support retrained model rollout with version tags.

Prediction contract:

```json
{
  "route": "/api/orders",
  "model_version": "v1",
  "predicted_rps": 520.0,
  "spike_probability": 0.82,
  "risk_level": "high",
  "prediction_horizon_sec": 60
}
```

### 4. Decision Engine

Responsibilities:

- Consume `aggregated_features` and `ml_predictions`.
- Evaluate traffic policy rules.
- Publish policy updates to `traffic_decisions`.
- Emit audit events for explainability.

Decision contract:

```json
{
  "route": "/api/orders",
  "decision_time": 1712601025,
  "policy_type": "RATE_LIMIT",
  "rate_limit_rps": 300,
  "reason": "predicted_rps_exceeds_capacity",
  "ttl_sec": 120
}
```

## Decision Rules

- If `predicted_rps > service_capacity_rps`, reduce route limit to safe operating range.
- If `p95_latency_ms > sla_p95_ms`, shift traffic to backup instances or shed non-critical requests.
- If `spike_probability >= 0.80`, pre-scale gateway or backend workers.
- If ML inference is unavailable, revert to static emergency policy.

## Storage Strategy

### Redis

- Stores recent windows and active policies.
- Key examples:
  - `traffic_window:/api/orders`
  - `active_policy:/api/orders`

### PostgreSQL

- Stores historical aggregates, model training sets, and policy audit data.

Tables:

- `traffic_history`
- `prediction_history`
- `policy_decisions`

## Failure Handling

- Kafka unavailable: gateway buffers minimally and drops metrics before impacting request path.
- Redis unavailable: feature service continues durable writes to PostgreSQL and rebuilds hot state later.
- ML unavailable: decision engine uses threshold-based fallback logic.
- Decision engine unavailable: gateway keeps last known good policy until TTL expiry.

## Initial Capacity Assumptions

- Gateway target: 10k RPS baseline, spike to 50k RPS.
- Feature aggregation window: 10s, 30s, 60s.
- Prediction horizon: 60s for MVP.
- Control loop target: update policy within one aggregation window.
