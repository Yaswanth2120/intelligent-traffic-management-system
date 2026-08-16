# Load-testing results

## Scope and SLO interpretation

Chaos testing was confirmed before this run; see `docs/chaos-testing.md` and
the committed artifacts in `docs/results/chaos/`. These tests measure the
gateway route and the ML service **separately**. `docs/slo.md` defines latency
and error objectives only for ML endpoints, so gateway rows are observational
(`N/A` in the SLO column) rather than evaluated against invented limits.

Direct ML traffic uses `POST /predict/aggregate`, the primary Kafka pipeline
shape. Every request is valid, so the reported HTTP error rate needs no 422
adjustment. k6 enforces the ML objectives directly:

- p95 <= 150 ms;
- p99 <= 400 ms;
- HTTP failure rate <= 0.1% (there were no non-2xx responses).

## Reproducible environment

Measurements were taken on 2026-08-16 on a local MacBook Air (Apple M2, 8 CPU
cores, 8 GB RAM), macOS 26.6.2, Docker Desktop 29.4.1, and k6 0.54.0. The
stack was started by `tests/chaos/scripts/start-stack.sh`: Kafka, Redis, and
Postgres ran in Docker Compose; gateway, feature service, decision engine, and
ML service ran as their production local processes. k6 ran in the pinned
`grafana/k6:0.54.0` container and reached those processes through
`host.docker.internal`.

This is a single-node local result, not a Kubernetes capacity claim. CPU,
memory, Kafka lag, and Redis latency were not sampled at a fixed interval, so
no peak resource values are reported.

## Profiles

| Profile | Arrival profile | Duration |
|---|---:|---:|
| Baseline | 30 requests/sec | 45 sec |
| Sustained | 150 requests/sec | 60 sec |
| Spike | 30 -> 300 requests/sec over 15 sec, 300 for 30 sec, then 30 for 15 sec | 75 sec |

Each profile is applied independently to `GET /api/orders` (gateway) and
`POST /predict/aggregate` (ML). The direct ML route is constant per run,
which intentionally moves through the 47-request heuristic warmup and then
exercises the Holt-Winters path. The ML service's own
`ml_forecast_model_selection_total` metric confirmed this: baseline ran
1,304 Holt-Winters requests after 47 warmup calls; sustained ran 8,954; and
spike ran 14,352.

## Measured results

All latency values are milliseconds. Throughput is achieved HTTP requests per
second; total is all requests attempted by k6. Raw k6 summary exports are in
`docs/results/load/`.

| Scenario | Endpoint | Throughput | Total | p50 | p95 | p99 | HTTP error rate | Meets ML SLO? |
|---|---|---:|---:|---:|---:|---:|---:|---|
| Baseline | Gateway `/api/orders` | 29.96 rps | 1,348 | 2.24 | 5.11 | 291.94 | 0.00% | N/A |
| Baseline | ML `/predict/aggregate` | 30.01 rps | 1,351 | 3.76 | 5.35 | 10.13 | 0.00% | Yes |
| Sustained | Gateway `/api/orders` | 150.00 rps | 9,000 | 0.80 | 1.58 | 2.83 | 0.00% | N/A |
| Sustained | ML `/predict/aggregate` | 150.03 rps | 9,001 | 2.64 | 3.52 | 4.19 | 0.00% | Yes |
| Spike | Gateway `/api/orders` | 191.99 rps | 14,400 | 0.55 | 1.99 | 3.20 | 32.06% | N/A |
| Spike | ML `/predict/aggregate` | 191.98 rps | 14,399 | 2.13 | 4.19 | 9.41 | 0.00% | Yes |

The gateway spike's 4,617 non-2xx responses were all `429 Too Many Requests`.
This is intentional admission control: feature/ML/decision processing issued a
175 RPS rate-limit policy for `/api/orders`. It is not evidence that the ML
service failed—the concurrently comparable direct ML spike remained at zero
errors and p99 9.41 ms.

## Capacity interpretation

- **Comfortable observed range:** both endpoints completed 150 RPS sustained;
  direct ML p99 was 4.19 ms, far under its 400 ms SLO.
- **Degradation/breaking observation:** at a 300 RPS offered gateway spike,
  the gateway began enforcing a 175 RPS policy and returned 429s (32.06% over
  the entire ramp/plateau run). This is the first observed application-level
  rejection point, not a latency collapse.
- **Primary bottleneck:** the gateway's policy enforcement/admission limit,
  driven by the feature-pipeline/decision feedback loop. It is not the direct
  ML HTTP service on this host: ML accepted the full 300 RPS spike target
  without errors or SLO breach. A true maximum ML capacity was not measured;
  doing so would require profiles above 300 RPS.

No performance fix was made: the measured gateway rejections are the
configured safety policy working as designed, and the ML results do not show
an obvious in-scope performance defect.

## Commands

Start and later stop the reproducible local stack:

```bash
bash tests/chaos/scripts/start-stack.sh
# ...run one or more profiles...
bash tests/chaos/scripts/stop-stack.sh
```

Run each scenario (each command creates a timestamped raw JSON summary):

```bash
make -C tests/load baseline-gateway
make -C tests/load baseline-ml
make -C tests/load sustained-gateway
make -C tests/load sustained-ml
make -C tests/load spike-gateway
make -C tests/load spike-ml
```

The runner pins k6 and uses the scenario thresholds in
`tests/load/k6/performance.js`; direct ML thresholds are sourced verbatim from
`docs/slo.md`.
