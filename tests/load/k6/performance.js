import http from "k6/http";
import { check } from "k6";

// Run with PROFILE=baseline|sustained|spike and TARGET=gateway|ml.
// ML thresholds come directly from docs/slo.md. Gateway has no documented
// latency/error SLO, so its measurements are intentionally reported as
// observational rather than judged against fabricated limits.
const profile = __ENV.PROFILE || "baseline";
const target = __ENV.TARGET || "gateway";
const baseUrl = __ENV.BASE_URL || "http://localhost";
const route = __ENV.ROUTE || `load-${profile}-${target}`;

const profiles = {
  baseline: {
    executor: "constant-arrival-rate",
    rate: 30,
    timeUnit: "1s",
    duration: "45s",
    preAllocatedVUs: 20,
    maxVUs: 80,
  },
  sustained: {
    executor: "constant-arrival-rate",
    rate: 150,
    timeUnit: "1s",
    duration: "60s",
    preAllocatedVUs: 60,
    maxVUs: 240,
  },
  spike: {
    executor: "ramping-arrival-rate",
    startRate: 30,
    timeUnit: "1s",
    preAllocatedVUs: 80,
    maxVUs: 400,
    stages: [
      { target: 30, duration: "15s" },
      { target: 300, duration: "15s" },
      { target: 300, duration: "30s" },
      { target: 30, duration: "15s" },
    ],
  },
};

if (!profiles[profile]) {
  throw new Error(`Unsupported PROFILE=${profile}`);
}
if (!new Set(["gateway", "ml"]).has(target)) {
  throw new Error(`Unsupported TARGET=${target}`);
}

const isMl = target === "ml";
export const options = {
  scenarios: { load: profiles[profile] },
  // summary-export omits p99 unless explicitly requested; it is an ML SLO.
  summaryTrendStats: ["min", "med", "p(90)", "p(95)", "p(99)", "max"],
  thresholds: isMl
    ? {
        // docs/slo.md: p95 <= 150 ms, p99 <= 400 ms, 5xx/unhandled errors <= 0.1%.
        "http_req_duration{target:ml}": ["p(95)<=150", "p(99)<=400"],
        "http_req_failed{target:ml}": ["rate<=0.001"],
      }
    : {},
};

const mlPayload = JSON.stringify({
  route,
  window_start: Math.floor(Date.now() / 1000),
  window_size_sec: 60,
  requests_per_sec: 340.0,
  error_rate: 0.012,
  avg_latency_ms: 110.0,
  p95_latency_ms: 190.0,
  unique_clients: 82,
});

export default function () {
  const response = isMl
    ? http.post(`${baseUrl}:8000/predict/aggregate`, mlPayload, {
        headers: { "Content-Type": "application/json" },
        tags: { target: "ml" },
      })
    : http.get(`${baseUrl}:8080/api/orders`, {
        headers: { "X-Client-Id": `k6-${__VU}` },
        tags: { target: "gateway" },
      });

  check(response, {
    "request returned success": (r) => r.status >= 200 && r.status < 300,
  });
}
