# Chaos Testing: Kafka and Redis Outages

Two reproducible resilience experiments, actually executed against the local
stack (`tests/chaos/scripts/start-stack.sh`), evaluated against
[`docs/slo.md`](slo.md) and the failure-handling contract in
[`docs/architecture.md`](architecture.md#failure-handling). Both experiments
found real bugs on their first run; both bugs were fixed, and both
experiments were rerun to confirm the fix, including under repeat-outage
conditions. Nothing in this document is a description of expected/theoretical
behavior — every number below came from an actual run, captured in
`docs/results/chaos/`.

## What the two scripts do

`tests/chaos/scripts/simulate-kafka-outage.sh` and
`simulate-redis-outage.sh` each:

1. Verify the stack is actually up (`require_stack_running` — fails fast
   with a clear message otherwise, not a confusing mid-experiment error).
2. Record a start timestamp, then `docker compose stop <kafka|redis>`.
3. Generate real HTTP traffic against the gateway for the outage window,
   logging `<timestamp>,<http_status>,<latency_ms>` per request.
4. Restore the dependency via `restore-kafka.sh` / `restore-redis.sh` (also
   called automatically on any exit path via a `trap`, so a mid-experiment
   failure still restores the dependency — no drill can leave the stack
   half-broken).
5. Poll Postgres/Redis until the pipeline visibly catches up (bounded wait,
   not a guess-and-hope fixed sleep) and measure recovery time.
6. Write a timestamped JSON result to `target/chaos/results/` and exit
   **non-zero if any invariant didn't hold** — an experiment that "ran" but
   found a real problem is a failing exit code, not just a log line.

Both `restore-*.sh` scripts are idempotent (safe to run standalone, safe to
run twice) and log the dependency's actual restore time.

## Reproduce it yourself

```bash
# 1. Start the stack (infra containers + all four services as local processes)
bash tests/chaos/scripts/start-stack.sh

# 2. Run either experiment (repeatable; each run gets a unique result file)
bash tests/chaos/scripts/simulate-kafka-outage.sh
bash tests/chaos/scripts/simulate-redis-outage.sh

# Optional: override outage duration (default 20s)
OUTAGE_DURATION_SEC=30 bash tests/chaos/scripts/simulate-redis-outage.sh

# 3. Tear down
bash tests/chaos/scripts/stop-stack.sh
```

Logs land in `target/chaos/logs/`, JSON results in `target/chaos/results/`
(both gitignored — sanitized copies of representative runs are committed
under `docs/results/chaos/`).

## Results

| Scenario | Expected Behavior | Observed Behavior (before fix) | Observed Behavior (after fix) | Recovery Time | Data Loss | Result |
|---|---|---|---|---|---|---|
| **Kafka outage** | Per `docs/architecture.md`: gateway request path stays available, metrics are dropped (not blocked) while Kafka is down; feature pipeline goes stale (no new aggregates); decision engine stops issuing fresh policies but the gateway keeps the last known policy until TTL expiry; once Kafka returns, new traffic resumes flowing end to end. | **Gateway became 80% unavailable** (16/20 requests failed, avg latency 4072ms) — `KafkaProducer.send()` was blocking the WebFlux request thread for seconds per call because no `max.block.ms` bound was set, so an unreachable broker stalled the whole gateway, not just metric delivery. | Gateway stayed **100% available** (20/20 success, avg latency 182ms) during the outage. All 20 in-flight metric publishes failed fast and were dropped (matching the documented contract); once Kafka returned, new traffic resumed flowing and landed in Postgres within seconds. | Kafka broker: ~450-600ms to reopen its port; full pipeline (producer/consumer reconnect + new traffic flowing to Postgres): ~30s observed | During-outage metrics dropped by design (documented, not a bug); zero loss for anything sent before the outage or after recovery | **PASS** (after fix) — see `docs/results/chaos/kafka-outage-before-fix.json` / `kafka-outage-after-fix.json` |
| **Redis outage** | Per `docs/architecture.md`: "Redis unavailable: feature service continues durable writes to PostgreSQL and rebuilds hot state later" — i.e. fail OPEN on the cache, not closed on the whole pipeline. Gateway is unaffected (it never talks to Redis). After Redis returns, the hot-cache key rebuilds on the next event, no manual intervention. | **Contract violated**: `RedisFeatureWindowRepository.save()` re-threw on failure, aborting `TrafficMetricsConsumer.consume()` before it reached the Postgres write or the Kafka publish — **0 of 20 requests' data reached Postgres** while Redis was down. Gateway itself was unaffected (it doesn't touch Redis). | Gateway stayed 100% available throughout (unaffected, as expected). **All 20 requests' data reached Postgres while Redis was still down** (confirmed durable-write-continues behavior), and the Redis hot-cache key rebuilt correctly once Redis came back — verified reliable across back-to-back repeat outages, not just a single lucky run. | Redis: ~380-490ms to reopen its port; feature-service resumes normal (non-degraded) processing within ~1s of Redis returning | None — every request's durable write succeeded, during the outage and after recovery | **PASS** (after fix) — see `docs/results/chaos/redis-outage-before-fix.json` / `redis-outage-after-fix.json` |

`docs/slo.md`'s specific latency/error-rate numbers are scoped to `ml-service`'s
`/predict` endpoints, not the gateway or feature-service paths these two
experiments exercise — there is no separate published gateway SLO. The
applicable standard here is `docs/architecture.md`'s explicit Non-Functional
Requirements ("graceful degradation if ML service unavailable", "at-least-once
event processing") and Failure Handling contract, both quoted above; these
experiments are evaluated against that contract by name, not against invented
numbers. **This absence of a gateway/feature-service-specific SLO is itself a
gap worth flagging** — see Limitations below.

## Bugs found and fixed

Both experiments found a real, previously-unknown bug on their very first run
(not a contrived failure) — chaos testing did its job. Both fixes stayed
within the stated scope ceiling (≤3 files, no API/schema change) and were
verified by rerunning the experiment, including repeat-outage conditions for
the Redis fix specifically because the first fix attempt turned out to be
incomplete.

### 1. Kafka outage: gateway blocked on `KafkaProducer.send()`

**Root cause**: `KafkaProducer.send()` blocks the *calling* thread for up to
`max.block.ms` (default 60000ms) when it can't obtain topic metadata — e.g.
the broker is unreachable. `TrafficMetricsFilter` calls
`TrafficMetricPublisher.publish()` (which calls `kafkaTemplate.send()`) from
inside a WebFlux `doFinally()`, i.e. on a reactor-netty event-loop thread.
Reactor-netty runs a small, fixed-size event-loop pool, so blocking even one
of those threads stalls unrelated concurrent requests too — this is exactly
what the raw traffic log from the first run shows: the first few requests
succeed in tens of milliseconds, then every subsequent request takes
~5000-5100ms (our own `curl --max-time 5` giving up, not the server
responding) for the rest of the outage window
(`docs/results/chaos/kafka-outage-after-fix-during-traffic.csv` is the fixed
version for comparison — the before-fix raw CSV showed the same pattern in
`target/chaos/logs/` at experiment time, not committed since it's
regeneratable and only the summary JSON was kept).

**Fix** (3 files, no API/schema change): added
`spring.kafka.producer.properties.max.block.ms: 2000` to
`gateway-service/application.yml`, `feature-service/application.yml`, and
`decision-engine/application.yml`. Gateway was the one directly on an HTTP
request path (so the highest-severity instance), but feature-service and
decision-engine have the identical pattern on their `@KafkaListener` consumer
threads — an unbounded block there would stall all Kafka consumption for
every route, not just message publish, so all three got the same bound for
consistency.

**Verified**: rerun after the fix (`kafka-outage-after-fix.json`) shows
100% gateway availability and 182ms average latency during the outage, vs.
80% failure and 4072ms before.

### 2. Redis outage: a cache failure silently dropped Postgres writes and Kafka publishes

**Root cause**: `TrafficMetricsConsumer.consume()` called
`redisRepository.save(aggregated)` *before*
`trafficHistoryRepository.save(aggregated)` and `publisher.publish(aggregated)`,
with no exception handling. `RedisFeatureWindowRepository.save()` re-throws on
any Redis failure, so the whole `@KafkaListener` method aborted before
reaching the Postgres write or the downstream Kafka publish — this directly
contradicts `docs/architecture.md`'s explicit contract: *"Redis unavailable:
feature service continues durable writes to PostgreSQL and rebuilds hot state
later."* First run: 0 of 20 requests' aggregates reached Postgres while Redis
was down (`redis-outage-before-fix.json`).

**Fix, attempt 1** (1 file): wrapped the `redisRepository.save(...)` call in
`TrafficMetricsConsumer.java` in a try/catch that logs and continues to the
Postgres write and Kafka publish regardless. This fixed a *single* outage
(first rerun: 20/20 rows landed during the outage) — but running the
experiment **a second time back-to-back, without restarting the stack**,
reproduced 0/20 again. Diagnosis: Lettuce's default Redis command timeout is
60 seconds, and once the client has already been through one
disconnect/reconnect cycle, commands issued during a *second* outage can sit
queued waiting for reconnection instead of failing immediately — so the
try/catch was technically correct but only fired after a long stall the
20-second experiment window didn't wait long enough to see complete.

**Fix, attempt 2** (1 more file, same fix area): added
`spring.data.redis.timeout: 2000ms` to `feature-service/application.yml`, so
Redis commands fail fast (matching the same "fail fast, don't block" pattern
as the Kafka producer fix) regardless of prior reconnect history. Total for
this fix: 2 files (`TrafficMetricsConsumer.java`,
`feature-service/application.yml`) — still within the 3-file scope ceiling.

**Verified**: reran the experiment **twice consecutively without restarting
the stack** (the exact scenario that exposed the incomplete first fix) —
both runs show all 20 in-outage requests' data reaching Postgres, and the
Redis hot-cache key rebuilding correctly after recovery
(`redis-outage-after-fix.json`; a third and fourth consecutive run were also
executed and behaved identically, confirming this isn't a lucky single
result).

### A methodology bug in the experiment scripts themselves, also found and fixed

Early runs of both scripts filtered Postgres rows by
`traffic_history.timestamp >= outage_start_epoch`, assuming that column was a
row-insert time. It isn't — it's `RouteWindowAggregate`'s **aggregation
window start** (the oldest event still in the current 60-second sliding
window), which lags behind wall-clock time under continuous traffic. This
made an early Redis-outage rerun *look* like it failed (0 rows "since outage
start") when the rows were actually there, just outside that filter. Fixed by
switching both scripts to simple total-row-count deltas (count before the
outage vs. after, not a timestamp filter) and polling for the pipeline to
catch up instead of a fixed sleep
(`tests/chaos/scripts/lib.sh::poll_until_stable`). This is a fix to the test
tooling, not application code, so it isn't subject to the 3-file scope
ceiling, but is documented here because it directly explains why some result
files in `target/chaos/results/` (not the ones committed to
`docs/results/chaos/`) show contradictory-looking numbers from mid-session
debugging.

## Limitations

- **No gateway/feature-service-specific SLO exists.** `docs/slo.md` only
  covers `ml-service`. These experiments are judged against
  `docs/architecture.md`'s qualitative failure-handling contract instead of
  numeric thresholds. Defining explicit gateway availability/latency
  objectives (mirroring the ml-service SLO doc's structure) would let future
  chaos runs assert a hard number instead of "stayed available."
- **Single-broker, single-outage experiments.** Not tested: multi-broker
  Kafka failover, partition rebalancing under load, Postgres itself going
  down, or an outage lasting longer than the Kafka producer's
  `delivery.timeout.ms` (~120s default) — a sustained outage beyond that
  window would behave differently (full producer buffer exhaustion) and
  wasn't exercised here.
- **ML service and decision engine outages are documented in
  `tests/chaos/README.md` but have no experiment script yet** (only Kafka and
  Redis were in scope here).
- **The Redis outage fix trades a small window of stale cache reads for
  pipeline availability.** `traffic_window:<route>` in Redis can be briefly
  stale (up to the next successfully-cached write) during and immediately
  after an outage; anything reading that key directly (not through this
  pipeline) should be aware it's a best-effort cache, not a durable source of
  truth — which was already true per `docs/architecture.md`, just now
  actually enforced by the code.
