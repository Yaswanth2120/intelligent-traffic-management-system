# Testing

Three tiers, kept deliberately separate so the fast ones stay fast.

| Tier | What | Command | Speed |
|---|---|---|---|
| Unit (Java) | Pure logic, no Spring context, no Docker | `mvn -pl gateway-service,feature-service,decision-engine test` | seconds |
| Unit (Python) | ML service logic, no Kafka | `cd ml-service && make test` (`pytest -q`) | seconds |
| Integration | Full distributed pipeline, real Kafka/Redis/Postgres + real service Docker images | `mvn -f integration-tests/pom.xml verify` | ~5 minutes (needs Docker) |
| Manual E2E smoke | Same pipeline via docker-compose + local JVM/uvicorn processes | `bash tests/e2e/run-local-e2e.sh` | ~2 minutes (needs Docker) |
| All | Everything above | see "All tests" below | — |

## Unit tests

```
mvn -pl gateway-service,feature-service,decision-engine test   # Java
cd ml-service && make test                                     # Python
```

No Docker required. This is what CI's `platform-validation.yml` `java-services` /
`ml-service` jobs run on every push/PR.

## Integration tests (Testcontainers)

```
mvn -f integration-tests/pom.xml verify
```

Requires a working Docker daemon (Docker Desktop, Colima, etc.). Runs
`integration-tests/src/test/java/com/traffic/integration/TrafficPipelineIT.java`.

**Why a separate Maven module, not `-pl integration-tests` from the root
reactor:** every service's Dockerfile (`feature-service/Dockerfile`,
`decision-engine/Dockerfile`, `ml-service/Dockerfile`) `COPY`s the root
`pom.xml` plus only the sibling `pom.xml` files it needs into its own build
context. Registering `integration-tests` as a root reactor module broke those
builds (the reactor then expects `integration-tests/pom.xml` to exist inside
that build context too). `integration-tests/pom.xml` still declares the root
pom as its Maven `<parent>` (for BOM-managed dependency versions), it's just
not in the root's `<modules>` list — so it has to be built with `-f` (or `cd
integration-tests && mvn verify`), not `-pl`.

**Why the test classes are named `*IT.java`, not `*Test.java`:** that's the
Maven Failsafe convention. `integration-tests/pom.xml` explicitly disables
Surefire (`mvn test` never touches this module) and binds Failsafe to
`integration-test`/`verify`. So `mvn test` anywhere in this repo stays fast;
only an explicit `mvn verify` (or the command above) pays the Docker cost.

### What it proves

The full flow, produced by REAL infrastructure and REAL service processes —
nothing mocked:

```
traffic_metrics (Kafka, published by the test, standing in for the gateway)
  -> feature-service (real Spring Boot container, built from its actual Dockerfile)
     -> Redis (traffic_window:<route> state, asserted via a real Jedis client)
     -> Postgres (traffic_history row, asserted via real JDBC)
     -> aggregated_features (Kafka)
aggregated_features -> ml-service (real FastAPI container, built from its
                                    actual Dockerfile; real Holt-Winters /
                                    heuristic prediction logic, per
                                    docs/forecasting.md)
  -> ml_predictions (Kafka)
ml_predictions -> decision-engine (real Spring Boot container)
  -> Postgres (policy_decisions row)
  -> traffic_decisions (Kafka, the final business output)
```

**The ML service leg is explicitly covered, not skipped.** The happy-path
test (`happyPath_highRiskTraffic_flowsThroughRealMlServiceToRateLimitDecision`)
independently recomputes the expected `predicted_rps` from the production
heuristic formula (`ml-service/app/predictor.py::_heuristic_predicted_rps`)
and asserts the real ml-service container's Kafka output matches it — this
proves an actual computed prediction came back, not just "some JSON arrived
on the topic."

Five test methods, each using its own randomly generated route (see
"Isolation" below):

1. **Happy path** — a burst of failing/slow requests flows through every hop
   to a `RATE_LIMIT` (high-risk, 175 rps) decision, with Redis, Postgres, and
   every Kafka topic along the way asserted.
2. **Multiple events / ordering** — 5 sequential events accumulate correctly
   in the sliding-window aggregate (final `unique_clients`, `requests_per_sec`,
   and `avg_latency_ms` all reflect every event, in order, none dropped).
3. **Low-risk traffic never escalates to high risk** — 30 benign requests
   never produce a high-risk decision (see the note in the test about a
   documented low-volume rounding quirk in the existing heuristic).
4. **Medium-risk traffic** — a mixed error-rate scenario produces the
   `RATE_LIMIT` medium-risk (220 rps) decision, exercising the decision
   engine's middle policy branch.
5. **Malformed event handling** — a non-JSON message and a schema-violating
   JSON message are published to `traffic_metrics`; the test then asserts a
   *subsequent, valid* event still flows all the way to `aggregated_features`.
   This is the test that caught and drove the `ErrorHandlingDeserializer` fix
   below.

### What it does NOT prove

- **The Holt-Winters seasonal model's switch-over.** Every test route is
  brand new, so ml-service always uses its heuristic fallback path
  (`baseline-v2-aggregate`) — a route never accumulates the 48 hourly points
  needed to switch to `holt-winters-v1` (see `docs/forecasting.md`). Forcing
  that here would mean 48+ round trips through 3 containers per test, which
  is slow and duplicates coverage the Python unit/eval suite already proves
  thoroughly (`ml-service/test_forecasting.py`,
  `ml-service/scripts/evaluate_forecast.py`). The *fallback* path is what's
  exercised end-to-end here.
- **The real gateway.** The described pipeline starts at `traffic_metrics`;
  the test publishes directly to that topic with plain Kafka clients, standing
  in for `gateway-service`. `gateway-service` itself is exercised separately
  by the manual E2E script and its own unit tests.
- **Kafka broker failure / restart mid-flight, partition rebalancing under
  load, or multi-broker behavior.** Single-broker KRaft Kafka only.
- **Prometheus/Grafana scraping or dashboards.** Not part of this pipeline.
- **Idempotency / exactly-once processing.** feature-service has no
  deduplication key; re-publishing the same `traffic_metrics` event twice
  would double-count it. This is existing, undocumented-until-now behavior of
  the at-least-once Kafka pipeline, not something this suite asserts is safe.
- **Load-bearing throughput.** These are correctness tests at small message
  volumes (single digits to tens of events per test), not the load tests in
  `tests/load/k6` (which should reference the latency/error-rate objectives in
  `docs/slo.md`).

### Isolation strategy

Every test method uses its own randomly generated route name
(`"orders-" + UUID.randomUUID()`), and every Kafka read uses a freshly
generated consumer group id reading from the earliest offset. That gives full
test isolation **without** needing to delete Kafka topics or clear
Redis/Postgres rows between tests — a route collision across tests is
statistically impossible, and each test's own Kafka consumer never shares
committed offsets with another test or with the real feature-service/
decision-engine consumer groups (`feature-service`, `decision-engine`, which
are fixed by `@KafkaListener(groupId = ...)` and never collide with a
test's random UUID group id either).

### Known gaps this suite found (and what was/wasn't fixed)

Building and actually *running* the real containers — not just starting them
— surfaced three genuine, pre-existing defects unrelated to the ML/
forecasting work. Given the stated scope ceiling (stop and report instead of
implementing if a fix needs more than ~3 files or an API change), each was
judged individually:

1. **Fixed** — `spring-boot-maven-plugin` had no `<execution>` binding in the
   root `pom.xml`'s `pluginManagement` (this project doesn't inherit from
   `spring-boot-starter-parent`, which normally supplies that binding
   implicitly). Every service's `mvn package` was silently producing a
   ~20KB plain jar with no `Main-Class` manifest entry instead of a runnable
   Spring Boot fat jar — every container failed at startup with `no main
   manifest attribute, in app.jar`. One-line fix, one file (root `pom.xml`).
   This blocked literally every container from starting, so it wasn't
   optional.
2. **Fixed** — `feature-service` has no web starter (no Tomcat/Netty) on its
   classpath, only `spring-boot-starter-actuator`, so it never opens an HTTP
   port — `/actuator/health` and `/actuator/prometheus` are unreachable
   despite `application.yml` configuring `server.port` and actuator endpoint
   exposure. **This was NOT fixed** (adding a web starter is a bigger,
   riskier change than this task's scope, and out of place for a background
   Kafka-consumer service that was never meant to serve HTTP). Both the
   integration test suite and the manual E2E script were adapted to use
   Spring Boot's "Started X in Ns" log line as the readiness signal instead
   of an HTTP health check — this is a real, load-bearing gap for anyone
   relying on the documented `/actuator/health` or `/actuator/prometheus`
   endpoints (e.g., Kubernetes probes, per `infra/k8s`, or Prometheus
   scraping) for `feature-service` and `decision-engine`; it should be
   revisited before either service is deployed to an environment that
   depends on those endpoints.
3. **Fixed** — `RedisFeatureWindowRepository` requires a
   `com.fasterxml.jackson.databind.ObjectMapper` bean, but with no web
   starter present, Spring Boot's `JacksonAutoConfiguration` never registers
   one (it needs `Jackson2ObjectMapperBuilder` from `spring-web`, which isn't
   on the classpath). `feature-service` failed to start at all. One-line fix,
   one file (`FeatureServiceConfig.java`, already the app's single
   `@Configuration` class).
4. **Fixed** — `traffic_metrics`'s `value-deserializer` was a bare
   `JsonDeserializer` with no `ErrorHandlingDeserializer` wrapper, so a single
   malformed message permanently stalled the `feature-service` consumer group
   on that offset (`RecordDeserializationException`, retried forever, every
   route's traffic blocked behind it — not just the sender of the bad
   message). This is a severe availability bug for any externally-fed topic.
   One-line fix, one file (`feature-service/application.yml`, wrapping the
   deserializer — Spring Kafka's default error handler already knows to log
   and skip past `ErrorHandlingDeserializer`-flagged records, no custom error
   handler bean needed).
5. **Fixed (test/dev tooling only, not application code)** —
   `infra/docker/docker-compose.yml`'s `confluentinc/cp-kafka:7.6.1` +
   `confluentinc/cp-zookeeper:7.6.1` images fail to start at all on Apple
   Silicon Docker Desktop as of this writing (`dub: cannot execute binary
   file: Exec format error` — the image's arm64 variant ships a broken helper
   binary). Swapped for a single-node KRaft `apache/kafka:3.7.1` service
   (native multi-arch, no separate zookeeper needed). This blocked
   `tests/e2e/run-local-e2e.sh` entirely on this machine; the integration
   test suite already used `apache/kafka` for the same reason.

Items 2's underlying gap (no HTTP actuator/metrics endpoint) is the most
consequential unresolved finding — it's flagged above rather than silently
worked around.

## Manual E2E smoke test

```
bash tests/e2e/run-local-e2e.sh
```

Brings up `infra/docker/docker-compose.yml`'s infra (Kafka, Redis, Postgres,
Prometheus, Grafana), builds and runs all four services as local
processes (`java -jar ...` / `uvicorn ...`), drives 20 requests through the
gateway, and checks: the gateway's own `/actuator/prometheus` metrics, a
real Redis key written by feature-service, no `ERROR`-level log lines from
feature-service/decision-engine, and ml-service's `/metrics`. A representative
successful run is saved at
`docs/results/e2e/run-local-e2e-2026-08-16.log` (sanitized: no secrets, purely
local file paths and log output).

## CI

`.github/workflows/platform-validation.yml` runs, on every push/PR:

- `java-services`: `mvn -pl gateway-service,feature-service,decision-engine test` (unit, fast)
- `ml-service`: Python unit tests, `evaluate_forecast.py`, model-registry validation (unit, fast)
- `integration-tests`: `mvn -f integration-tests/pom.xml verify` (Testcontainers, ~5 min, needs Docker — GitHub's `ubuntu-latest` runners have Docker preinstalled)
- `manifests-and-dashboards`: static YAML/JSON validation (unit, fast)

The manual E2E script is not wired into CI (it manages its own long-lived
local processes and host ports, which doesn't fit a CI job well); it's a
local developer/release-checklist tool, run manually as documented above.
