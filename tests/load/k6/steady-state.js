import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: 10,
  duration: "2m",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<400"],
  },
};

export default function () {
  const response = http.get("http://localhost:8080/api/orders", {
    headers: { "X-Client-Id": "k6-steady" },
  });

  check(response, {
    "steady-state status is 200 or 429": (r) => r.status === 200 || r.status === 429,
  });

  sleep(1);
}
