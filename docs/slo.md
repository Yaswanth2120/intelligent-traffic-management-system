# ML Prediction Service — Service Level Objectives

These SLOs cover the `ml-service` (`/predict` and `/predict/aggregate`). They are the
single source of truth for load-testing thresholds, autoscaling triggers, and alerting
rules — later work must reference this file rather than inventing new numbers.

## Context used to derive these numbers

- `docs/architecture.md` states the end-to-end control loop must close in **under 5
  seconds** for hot routes, and the decision engine consumes predictions to act within
  one aggregation window (10s/30s/60s).
- The gateway calls the ML service synchronously (via the decision engine's Kafka
  consumption or direct HTTP in tests) in the hot path of routing decisions, so
  prediction latency is a direct subtractive component of that 5s budget — it must be a
  small fraction of it, not close to it.
- The prediction horizon is 60s (MVP). A prediction is only useful if the features it
  was computed from are fresh relative to that horizon.

## Latency

| Metric | Objective | Justification |
|---|---|---|
| p95 prediction latency (`/predict`, `/predict/aggregate`) | **≤ 150 ms** | Leaves ample headroom inside the 5s end-to-end control-loop budget for Kafka hops, feature aggregation, and decision-engine policy evaluation, while still being tight enough that the ML call is never the bottleneck at the 95th percentile. |
| p99 prediction latency | **≤ 400 ms** | Bounds tail latency (GC pauses, cold model-buffer state, first request for a new route) to well under one aggregation window (10s), so a slow outlier request never causes a stale policy decision. |

## Error rate

| Metric | Objective | Justification |
|---|---|---|
| `/predict` error rate | **≤ 0.1% of requests (5xx / unhandled exceptions)** | The decision engine has an explicit fallback ("ML unavailable: decision engine uses threshold-based fallback logic" per `docs/architecture.md`), so the service must fail rarely enough that the fallback path stays a rare/emergency path, not routine behavior. |
| `/predict/aggregate` error rate | **≤ 0.1% of requests** | Same reasoning; this is the primary endpoint invoked by the Kafka prediction pipeline. |

Validation errors (HTTP 422 from malformed request bodies) are excluded from this
budget — they indicate a caller/schema bug, not a service reliability failure, and are
tracked separately.

## Forecast staleness

| Metric | Objective | Justification |
|---|---|---|
| Max age of features used in a prediction | **≤ 120 s (2× the 60s prediction horizon)** | A forecast built on data older than roughly two horizons no longer reflects current traffic conditions (per the periodic/spike patterns in `docs/forecasting.md`) and risks the decision engine acting on a stale signal. Feature windows are produced every 10–60s per `docs/architecture.md`, so this leaves margin for one missed aggregation cycle before staleness matters. |
| Max age of historical buffer used to fit the seasonal forecasting model | **≤ 7 days** | Bounds how far back the in-memory per-route history window can lag before its seasonal/trend estimate is considered untrustworthy and the service falls back to the non-seasonal baseline. |

## How these are used downstream

- **Load testing** (`tests/load/k6`): pass/fail thresholds for `/predict` and
  `/predict/aggregate` must assert p95 ≤ 150ms and p99 ≤ 400ms as defined here.
- **Autoscaling**: HPA/KEDA triggers for `ml-service` should scale out before p95
  latency approaches the 150ms objective (e.g., scale on a latency or CPU proxy metric
  with headroom under this ceiling), not after it is breached.
- **Alerting**: page or ticket when error rate exceeds 0.1% over a 5-minute window, or
  when p99 latency exceeds 400ms for more than 5 consecutive minutes.
