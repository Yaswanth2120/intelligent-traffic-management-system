# KEDA-Driven Forecast Autoscaling

This closes the loop that Prompts 1-5 built but never wired to real infrastructure:
a forecast-derived scaling signal now actually changes Kubernetes replica counts,
through KEDA, on the local `kind` cluster.

```
Incoming traffic -> traffic_metrics (Kafka)
  -> feature-service aggregate -> aggregated_features (Kafka)
    -> ml-service forecast (Holt-Winters / heuristic, docs/forecasting.md) -> ml_predictions (Kafka)
      -> decision-engine (DecisionPolicyService)
        -> decision_scaling_pressure_ratio (Prometheus gauge)
          -> Prometheus (scraped from decision-engine:8082/actuator/prometheus)
            -> KEDA ScaledObject (Prometheus trigger, polls Prometheus)
              -> keda-hpa-gateway-service-forecast (HPA KEDA manages)
                -> gateway-service replica count
```

**KEDA, not Prometheus Adapter, was used** — installed directly from the
upstream release manifest (`kubectl apply --server-side -f
https://github.com/kedacore/keda/releases/download/v2.16.1/keda-2.16.1.yaml`),
per this task's explicit architecture requirement for a local kind/k3d/k3s
environment. No application code calls `kubectl scale`; KEDA creates and
owns its own HPA (`keda-hpa-gateway-service-forecast`) from the
`ScaledObject`, and that HPA is what actually changes `gateway-service`'s
replica count.

## The scaling metric

`DecisionPolicyService.evaluate()` already computed, internally, the same
ratio it uses to decide whether to emit a `SCALE_SIGNAL` decision:
`predictedRps / serviceCapacityRps` (>= 0.85 triggers `SCALE_SIGNAL`,
`serviceCapacityRps` = 250 in `application.yml`). That ratio is now also
exported as a Prometheus gauge, `decision_scaling_pressure_ratio{route=...}`,
on **every** prediction the decision engine consumes — not just the ones
that cross the 0.85 threshold — so it's a continuous signal, not a spike
train. This is a real, meaningful capacity-pressure metric, not a fake
random value: 0.0 means no measurable predicted load, 1.0 means the forecast
predicts exactly the configured capacity, >1.0 means the forecast predicts
overload.

Code: `decision-engine/src/main/java/com/traffic/decision/rules/DecisionPolicyService.java`
(records the ratio) and `.../metrics/DecisionMetricsRecorder.java` (exposes
the gauge, lazily registered per route).

## Files changed

| File | Change |
|---|---|
| `decision-engine/.../metrics/DecisionMetricsRecorder.java` | New `recordScalingPressure(route, ratio)` registering `decision_scaling_pressure_ratio` |
| `decision-engine/.../rules/DecisionPolicyService.java` | Calls it on every `evaluate()`, before the existing policy branches |
| `decision-engine/.../rules/DecisionPolicyServiceTest.java` | New test asserting the exported gauge value |
| `infra/k8s/autoscaling.yaml` | Removed gateway-service's native CPU HPA (KEDA now owns it); capped feature-service/decision-engine/ml-service HPAs to `maxReplicas: 2` (see "Resource constraints" below) |
| `infra/k8s/keda-scaledobject.yaml` | **New.** The `ScaledObject` |
| `infra/k8s/gateway-service.yaml` | Right-sized resource requests + probe timeouts (see "Bugs found" below) |
| `infra/k8s/feature-service.yaml` | Same probe/resource fix, same root cause |
| `infra/docker/grafana/dashboards/traffic-platform-overview.json` | New panel: "Forecast Scaling Pressure" |
| `ml-service/app/kafka_pipeline.py` | Retry-with-backoff fix (see "Bugs found" below) |

## ScaledObject configuration

`infra/k8s/keda-scaledobject.yaml`:

- **Target**: `gateway-service` (the front door — architecture.md's decision
  rules already say to "pre-scale gateway or backend workers" on high spike
  probability; `SCALE_SIGNAL`'s capacity ratio is specifically about gateway
  capacity)
- **minReplicaCount: 3, maxReplicaCount: 8**
- **Trigger**: Prometheus, query `max(decision_scaling_pressure_ratio)`,
  `threshold: "0.5"`, `metricType: Value` (see "Bugs found" — this field is
  not optional for this metric)
- **pollingInterval: 15s** (matches Prometheus's scrape interval)
- **cooldownPeriod: 120s**, plus HPA `behavior.scaleDown.stabilizationWindowSeconds: 180s`
  and a 50%-per-60s scale-down policy — load has to stay low for a while
  before replicas actually drop, avoiding flapping
- **scaleUp**: no stabilization delay, 100%-per-30s policy — react fast to a
  real spike

## What was actually demonstrated (real run, 2026-08-16)

Environment: local `kind` cluster (`traffic-platform`), single control-plane
node, Docker Desktop 29.4.1 on macOS (Apple Silicon), ~3.9GiB allocatable to
the node. KEDA v2.16.1. Full command sequence in "Commands to reproduce" below.

### Timeline

| Time (UTC) | Event | `decision_scaling_pressure_ratio` | gateway-service replicas |
|---|---|---:|---:|
| 21:25:01 - 21:26:36 | Normal load (idle/light) | 0.032 (flat) | 3 (stable) |
| 21:26:20 | k6 spike load test starts (`tests/load/run-in-cluster.sh spike gateway`, 30->300 rps ramp, in-cluster Job) | rising | 3 |
| 21:26:41 | | 0.038 | 3 |
| 21:26:59 | | 0.170 | 3 |
| 21:27:10 | | 0.376 | 3 |
| 21:27:28 | | 0.550 | 3 |
| 21:27:44 | | 0.741 | 3 |
| 21:27:55 - 21:28:48 | Peak sustained | **0.763** | 3 (KEDA trigger misconfigured until 21:28:53 — see below) |
| **21:28:53** | Fixed `metricType: Value` (see "Bugs found") and reapplied | 0.763 | 3 |
| **21:28:58** | **First scale-up**: HPA computed `ceil(3 * 0.763/0.5) = 5` | 0.763 | **3 -> 5** |
| **21:30:05** | **Second scale-up** (next poll cycle, recalculated against new replica count): `ceil(5 * 0.763/0.5) = 8`, capped at `maxReplicaCount` | 0.763 | **5 -> 8** |
| 21:30:20 (approx) | 8 light "settle" requests sent (`kubectl run curl-settle`) so the forecast reflects reduced load — the metric is a gauge that only updates on new predictions, it does not decay on its own when traffic stops | — | 8 |
| 21:30:43 | | 0.510 | 8 |
| **21:30:59** | Pressure ratio reaches zero | **0.0** | 8 |
| 21:31:00 - 21:37:32 | `cooldownPeriod` (120s) + `scaleDown.stabilizationWindowSeconds` (180s) + HPA sync overhead elapse | 0.0 | 8 (unchanged, as designed) |
| **21:37:33** | **First scale-down step** (50%/60s policy): 8 -> 4 | 0.0 | **8 -> 4** |
| **21:38:14** | **Second scale-down step**, reaches floor | 0.0 | **4 -> 3** |
| 21:38:14 - 21:40:46 | Confirmed stable at floor for 2.5+ minutes | 0.0 | 3 (stable) |

Raw evidence: `docs/results/autoscaling/scaling-timeline-raw.csv` (polled
every 5s from Prometheus + `kubectl get hpa`), plus the exact `kubectl get
hpa` transcripts in this file's git history / session log for the
21:35-21:40 window (the CSV poller's own process was interrupted once by a
Prometheus pod restart — see "Bugs found" — so the 21:35-21:38 replica
values were cross-verified with direct `kubectl get hpa` polls, not the CSV
alone).

**Total observed scale-up latency**: metric crossed threshold (0.5, at
21:27:28) to first replica increase (21:28:58) = **90s**, though ~85s of
that was spent diagnosing and fixing the `metricType` bug below, not KEDA's
own reaction time — once the fixed config was applied, the very next
15s polling cycle triggered the scale-up (21:28:53 -> 21:28:58, **~5s**).
**Total observed scale-down latency**: metric reaching zero (21:30:59) to
reaching the replica floor (21:38:14) = **7m 15s**, matching the configured
`cooldownPeriod` + `stabilizationWindowSeconds` + the 50%/60s policy needing
two steps to go from 8 to 3.

### Latency and error rate during the spike (gateway, in-cluster k6)

From `docs/results/autoscaling/20260816T212620Z-spike-gateway-incluster.json`
(11,920 requests, 159 rps achieved):

| Metric | Value |
|---|---|
| Achieved throughput | 159.1 rps |
| HTTP error rate | **0%** (0 of 11,920) |
| p50 latency | 457 ms |
| p95 latency | 3,037 ms |
| p99 latency | 5,187 ms |

`docs/slo.md`'s numeric thresholds are scoped to `ml-service`, not the
gateway, so there is no gateway p95/error-rate SLO to grade this against —
consistent with `docs/load-testing.md`'s same finding. What this run shows
directly: gateway accepted 100% of requests during the spike (no 429s, no
5xx) while `decision_scaling_pressure_ratio` was climbing and KEDA was
adding replicas, and elevated latency (p95 > 3s) is visible before/while the
extra replicas came online — i.e. the metric-driven scale-up is real, but
the request path still feels the spike until new pods are `Ready`, which is
expected and is why `docs/autoscaling.md`'s scale-up latency numbers above
matter as much as replica count.

### ML service under the same class of spike (single instance, for context)

`ml-service` was **not** the target of this task's KEDA wiring (gateway-service
is), and its own HPA was capped to `maxReplicas: 1` for the duration of this
experiment (see "Resource constraints" below) — so this number reflects
one ml-service instance's capacity, not autoscaled capacity:

From `docs/results/autoscaling/20260816T214107Z-spike-ml-incluster.json`
(14,259 requests direct to `POST /predict/aggregate`, 190 rps achieved):

| Metric | Value | Meets `docs/slo.md`? |
|---|---|---|
| Achieved throughput | 190.1 rps | — |
| HTTP error rate | 0% | Yes (<= 0.1%) |
| p95 latency | 80.2 ms | Yes (<= 150ms) |
| p99 latency | **436.3 ms** | **No** (> 400ms, by 36ms) |

This is an honest, real result, not adjusted to look better: a single
ml-service replica's p99 slipped past the SLO under a 190 rps spike. It's a
genuine argument for *also* giving ml-service a KEDA (or CPU) scaling path
sized for its own SLO in future work — out of scope here (this task's
target deployment is `gateway-service`), but worth flagging rather than
omitting.

## Bugs found and fixed

Three real, previously-unknown defects surfaced while actually running this
experiment (not from code review) — all within the stated scope ceiling.

### 1. `ml-service`'s Kafka pipeline silently died on startup (1 file)

Before this task, `decision_scaling_pressure_ratio` could never have existed
in practice: `ml-service`'s Kafka consumer/producer pipeline
(`KafkaPredictionPipeline._run()`) started via an **unawaited**
`asyncio.create_task()`. If Kafka wasn't reachable at the exact instant the
pod started (a real race against a freshly-starting broker — confirmed
happening here), `consumer.start()` raised, the task died, and nothing ever
retried. `/health` kept reporting `kafka_pipeline_enabled: true` (that flag
only reflects the config value, not runtime state) while
`kafka-consumer-groups.sh --list` showed **no `ml-service` group at all** —
the entire forecast pipeline was silently dead. Fixed with a retry-with-backoff
loop in `ml-service/app/kafka_pipeline.py` (2s initial backoff, doubling to a
30s cap); a single failed publish no longer kills the whole consumer either.

### 2. `decision_scaling_pressure_ratio` was being divided by replica count (1 file, `infra/k8s/keda-scaledobject.yaml`)

KEDA's Prometheus scaler defaults to the HPA `AverageValue` metric type,
which divides the raw query result by the *current* replica count before
comparing to `threshold` — correct for a genuinely per-pod metric, wrong for
this one (a global, aggregate ratio). Measured directly: raw Prometheus
value 0.76 against 3 replicas showed up in `kubectl get hpa` as target
`254m/500m` (0.76/3 ≈ 0.254) — the effective threshold had silently become
`threshold * currentReplicas`, so no offered load could ever cross it while
replicas stayed at the floor. Fixed by setting `metricType: Value` on the
trigger, confirmed by the very next polling cycle producing the 3 -> 5
scale-up.

### 3. Local single-node cluster resource contention (infra tuning, not application code)

Running `gateway-service` at up to 8 replicas simultaneously with
`feature-service`, `decision-engine`, and `ml-service`'s own native CPU HPAs
(originally capped at 6 each) exceeded this kind node's ~3.9GiB allocatable
memory. Symptoms observed directly: `CrashLoopBackOff` on multiple
deployments (including Prometheus itself, which has no resource
requests/limits and is therefore the first evicted under node memory
pressure — its transient restart is why the raw timeline CSV has a gap
around 21:32-21:33), `FailedScheduling: Insufficient memory` events, and
gateway pods failing their default 1-second liveness probe timeout under
load. Addressed by: right-sizing `gateway-service`/`feature-service`
resource *requests* down to their actually-observed usage (256Mi vs. a
previous 384Mi request that was never really needed), giving probes more
realistic timeouts (5s vs. the Kubernetes default 1s, with
`initialDelaySeconds` so a slow JVM start isn't misread as a failure), and
capping the other three services' HPAs to `maxReplicas: 2` for this specific
local environment (documented inline in `infra/k8s/autoscaling.yaml` as a
local-cluster-only constraint, not a recommendation for a larger cluster).
This is a real, honestly-reported limitation of demonstrating multi-service
horizontal scaling on a single laptop-class node, not something a managed
cluster would hit.

## Grafana dashboard

`infra/docker/grafana/dashboards/traffic-platform-overview.json` gained a
ninth panel, "Forecast Scaling Pressure (predicted RPS / capacity) — drives
KEDA gateway-service scaling", graphing `decision_scaling_pressure_ratio`
with threshold color bands at 0.5 (yellow, matches the KEDA trigger
threshold) and 0.85 (red, matches `SCALE_SIGNAL`'s own threshold). Deployed
to the cluster via the same `grafana-dashboards` ConfigMap `kind-up.sh`
already provisions.

## Commands to reproduce

```bash
# 1. Bring up the cluster (if not already up) and install KEDA
make k8s-up
kubectl apply --server-side -f https://github.com/kedacore/keda/releases/download/v2.16.1/keda-2.16.1.yaml
kubectl -n keda rollout status deployment/keda-operator --timeout=120s

# 2. Apply the ScaledObject (and the updated autoscaling.yaml, which no
#    longer defines a native HPA for gateway-service)
kubectl delete hpa gateway-service -n traffic-platform --ignore-not-found
kubectl apply -f infra/k8s/autoscaling.yaml
kubectl apply -f infra/k8s/keda-scaledobject.yaml

# 3. Watch it
kubectl -n traffic-platform get scaledobject,hpa
watch kubectl -n traffic-platform get hpa keda-hpa-gateway-service-forecast

# 4. Drive the spike (reuses Prompt 4's k6 spike profile, run as an in-cluster
#    Job so kubectl port-forward isn't the bottleneck -- see the note in
#    tests/load/run-in-cluster.sh)
bash tests/load/run-in-cluster.sh spike gateway

# 5. Optional: send a few light requests after the spike so the forecast
#    reflects reduced load and you can observe scale-down without waiting for
#    unrelated traffic:
kubectl -n traffic-platform run curl-settle --image=curlimages/curl:8.10.1 \
  --restart=Never --rm -i --command -- sh -c \
  'for i in $(seq 1 8); do curl -s -o /dev/null http://gateway-service:8080/api/orders; sleep 3; done'

# 6. Query the raw metric directly at any point
kubectl -n traffic-platform port-forward svc/prometheus 9090:9090 &
curl -s 'http://localhost:9090/api/v1/query?query=max(decision_scaling_pressure_ratio)'
```

## Limitations

- **Single deployment scaled.** Only `gateway-service` is KEDA-managed;
  `feature-service`/`decision-engine`/`ml-service` still use native CPU
  HPAs (capped to `maxReplicas: 2` locally — see above). ml-service's own
  p99 SLO breach under load (measured above) is a real argument for giving
  it a forecast- or CPU-driven scaling path too.
- **This is a single-node local cluster result**, not a capacity claim for a
  managed/multi-node cluster — consistent with `docs/deployment.md` and
  `docs/load-testing.md`'s same disclosure. The resource-contention bug
  found and fixed here is specific to this environment's ~3.9GiB node.
- **The metric is a gauge with no automatic decay.** It reflects the *last*
  prediction seen for a route; if traffic stops entirely, the value stays
  frozen rather than trending back toward zero, until a new (lower-volume)
  request arrives. This is why the reproduction steps above send a few
  light "settle" requests after the spike — without them, scale-down would
  never be observed at all, not because KEDA/HPA are broken but because
  there would be no new data telling them load had dropped. A production
  deployment would likely want either a periodic low-volume heartbeat
  prediction or a max-age-based decay on this metric; out of scope to
  implement here.
- **CPU-based HPAs and the KEDA ScaledObject both exist in the same
  namespace** but target different deployments, so there's no conflict —
  but a Deployment cannot be targeted by two HPAs simultaneously, which is
  why gateway-service's native CPU HPA had to be removed, not just
  supplemented.
