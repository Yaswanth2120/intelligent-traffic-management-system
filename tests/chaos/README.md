# Chaos Drills

Reproducible resilience experiments for Kafka and Redis outages, with
documented, actually-observed results evaluated against `docs/slo.md` and
`docs/architecture.md`'s failure-handling contract. See
[`docs/chaos-testing.md`](../../docs/chaos-testing.md) for the full write-up
(results table, root causes, fixes, and their verification) and
`docs/results/chaos/` for sanitized before/after artifacts.

## Quick start

```bash
bash scripts/start-stack.sh              # infra containers + all 4 services
bash scripts/simulate-kafka-outage.sh    # or simulate-redis-outage.sh
bash scripts/stop-stack.sh
```

`OUTAGE_DURATION_SEC` (default 20) overrides how long the dependency stays
down. Each run writes a timestamped JSON result to `target/chaos/results/`
and exits non-zero if anything unexpected happened.

## Kafka Outage

- Gateway request path keeps serving traffic (2xx) throughout the outage.
- Metric publish failures rise (`gateway_metric_publish_total{result="failure"}`).
- Feature/decision pipeline goes stale (no new aggregates/predictions) but
  the gateway keeps enforcing the last known policy until TTL expiry.
- Once Kafka returns, new traffic resumes flowing end to end within seconds.

## Redis Outage

- Gateway is unaffected (it never talks to Redis directly).
- feature-service's Redis writes fail during the outage, but PostgreSQL
  history writes and downstream Kafka publishing continue regardless
  ("fail open" on the cache layer, per `docs/architecture.md`).
- The Redis hot-cache key rebuilds on the next event once Redis returns.

## ML Service Outage

- Expect no new `ml_predictions`
- Decision engine should stop issuing fresh prediction-based policies
- Gateway should continue enforcing cached active decisions until TTL expiry

(No automated experiment script yet for this one — see
`docs/chaos-testing.md` Limitations.)
