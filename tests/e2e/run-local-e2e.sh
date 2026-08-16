#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="${ROOT_DIR}/target/e2e-logs"
mkdir -p "${LOG_DIR}"

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-traffic-platform-e2e}"
export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-19092}"
export REDIS_HOST_PORT="${REDIS_HOST_PORT:-16379}"
export POSTGRES_HOST_PORT="${POSTGRES_HOST_PORT:-15432}"
export PROMETHEUS_HOST_PORT="${PROMETHEUS_HOST_PORT:-19090}"
export GRAFANA_HOST_PORT="${GRAFANA_HOST_PORT:-13000}"

cleanup() {
  for pid_file in "${LOG_DIR}"/*.pid; do
    if [[ -f "${pid_file}" ]]; then
      kill "$(cat "${pid_file}")" >/dev/null 2>&1 || true
    fi
  done
  if [[ "${KEEP_E2E_INFRA:-false}" != "true" ]]; then
    docker compose -f "${ROOT_DIR}/infra/docker/docker-compose.yml" down >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_tcp() {
  local port="$1"
  local name="$2"
  for _ in {1..60}; do
    if (echo >"/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1; then
      echo "${name} port is ready"
      return 0
    fi
    sleep 2
  done
  echo "${name} did not open port ${port}" >&2
  return 1
}

wait_for_http() {
  local url="$1"
  local name="$2"
  for _ in {1..60}; do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "${name} is ready"
      return 0
    fi
    sleep 2
  done
  echo "${name} did not become ready at ${url}" >&2
  return 1
}

# feature-service and decision-engine declare spring-boot-starter-actuator but
# have no web starter (no Tomcat/Netty) on their classpath, so they never open
# an HTTP port despite application.yml configuring server.port and actuator
# endpoint exposure -- /actuator/health is unreachable (see docs/testing.md
# "known gaps this suite found"). A Spring Boot app always logs "Started X in
# Ns" once its context refreshes successfully regardless of web/non-web mode,
# so that's used as the readiness signal for those two instead.
wait_for_log() {
  local log_file="$1"
  local pattern="$2"
  local name="$3"
  for _ in {1..60}; do
    if [[ -f "${log_file}" ]] && grep -q "${pattern}" "${log_file}" 2>/dev/null; then
      echo "${name} is ready"
      return 0
    fi
    sleep 2
  done
  echo "${name} did not log '${pattern}' in ${log_file}" >&2
  return 1
}

cd "${ROOT_DIR}"

docker compose -f infra/docker/docker-compose.yml up -d kafka redis postgres prometheus grafana

wait_for_tcp "${KAFKA_HOST_PORT}" "kafka"
wait_for_tcp "${REDIS_HOST_PORT}" "redis"
wait_for_tcp "${POSTGRES_HOST_PORT}" "postgres"

mvn -q -DskipTests package

KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" java -jar gateway-service/target/gateway-service-*.jar >"${LOG_DIR}/gateway.log" 2>&1 &
echo "$!" >"${LOG_DIR}/gateway.pid"

KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" POSTGRES_URL="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/traffic_platform" REDIS_PORT="${REDIS_HOST_PORT}" java -jar feature-service/target/feature-service-*.jar >"${LOG_DIR}/feature.log" 2>&1 &
echo "$!" >"${LOG_DIR}/feature.pid"

KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" POSTGRES_URL="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/traffic_platform" java -jar decision-engine/target/decision-engine-*.jar >"${LOG_DIR}/decision.log" 2>&1 &
echo "$!" >"${LOG_DIR}/decision.pid"

python3 -m venv "${LOG_DIR}/venv"
"${LOG_DIR}/venv/bin/pip" install -q -r ml-service/requirements.txt
ENABLE_KAFKA_PIPELINE=true KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" "${LOG_DIR}/venv/bin/uvicorn" app.main:app --app-dir ml-service --host 0.0.0.0 --port 8000 >"${LOG_DIR}/ml.log" 2>&1 &
echo "$!" >"${LOG_DIR}/ml.pid"

wait_for_http "http://localhost:8080/actuator/health" "gateway-service"
wait_for_log "${LOG_DIR}/feature.log" "Started FeatureServiceApplication" "feature-service"
wait_for_log "${LOG_DIR}/decision.log" "Started DecisionEngineApplication" "decision-engine"
wait_for_http "http://localhost:8000/health" "ml-service"
wait_for_http "http://localhost:${PROMETHEUS_HOST_PORT}/-/ready" "prometheus"
wait_for_http "http://localhost:${GRAFANA_HOST_PORT}/api/health" "grafana"

# "Started X Application" (used above as the readiness signal for
# feature-service/decision-engine) logs slightly before their Kafka consumer
# group has finished joining/getting a partition assignment. A short settle
# delay avoids a race where early traffic arrives before the consumer group
# is actually subscribed.
sleep 5

for _ in {1..20}; do
  curl -fsS -H "X-Client-Id: e2e-client" http://localhost:8080/api/orders >/dev/null
done

sleep 15

curl -fsS -X POST http://localhost:8000/predict/aggregate \
  -H "Content-Type: application/json" \
  -d '{"route":"orders-route","window_start":1,"window_size_sec":60,"requests_per_sec":340,"error_rate":0.12,"avg_latency_ms":180,"p95_latency_ms":420,"unique_clients":140}' >/dev/null

curl -fsS http://localhost:8080/actuator/prometheus | grep -q "gateway_requests_total"
# feature-service and decision-engine can't be checked over /actuator/prometheus
# (see wait_for_log comment above -- no HTTP server). Their real-world effect is
# checked instead: feature-service's Redis write, and both services' logs
# showing no errors while handling the traffic generated above.
docker compose -f "${ROOT_DIR}/infra/docker/docker-compose.yml" -p "${COMPOSE_PROJECT_NAME}" \
  exec -T redis redis-cli GET "traffic_window:orders-route" | grep -q "orders-route"
! grep -q "ERROR" "${LOG_DIR}/feature.log"
! grep -q "ERROR" "${LOG_DIR}/decision.log"
curl -fsS http://localhost:8000/metrics | grep -q "ml_prediction_requests_total"

echo "E2E smoke test passed"
