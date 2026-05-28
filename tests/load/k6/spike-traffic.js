import http from "k6/http";
import { check } from "k6";

export const options = {
  scenarios: {
    warmup: {
      executor: "ramping-vus",
      startVUs: 5,
      stages: [
        { duration: "30s", target: 20 },
        { duration: "30s", target: 60 },
      ],
      gracefulRampDown: "10s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<750"],
  },
};

export default function () {
  const response = http.get("http://localhost:8080/api/orders", {
    headers: { "X-Client-Id": "k6-spike" },
  });

  check(response, {
    "spike request is handled or throttled": (r) => r.status === 200 || r.status === 429,
  });
}
