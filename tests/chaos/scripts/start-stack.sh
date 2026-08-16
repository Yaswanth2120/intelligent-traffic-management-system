#!/usr/bin/env bash
# Brings up the full local stack (infra containers + all four services as
# local processes) and leaves it running for chaos experiments to run
# against. Unlike tests/e2e/run-local-e2e.sh this does NOT tear itself down
# on exit -- call stop-stack.sh explicitly when done.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "${SCRIPT_DIR}/lib.sh"

cd "${ROOT_DIR}"

log "Starting infra containers (project=${COMPOSE_PROJECT_NAME})..."
"${COMPOSE[@]}" up -d kafka redis postgres prometheus grafana

wait_for_tcp "${KAFKA_HOST_PORT}" "kafka"
wait_for_tcp "${REDIS_HOST_PORT}" "redis"
wait_for_tcp "${POSTGRES_HOST_PORT}" "postgres"

log "Building service jars..."
mvn -q -DskipTests package

log "Starting gateway-service, feature-service, decision-engine, ml-service..."

KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" \
  nohup java -jar gateway-service/target/gateway-service-*.jar >"${LOG_DIR}/gateway.log" 2>&1 &
echo "$!" >"${LOG_DIR}/gateway.pid"
disown

KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" \
  POSTGRES_URL="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/traffic_platform" \
  REDIS_PORT="${REDIS_HOST_PORT}" \
  nohup java -jar feature-service/target/feature-service-*.jar >"${LOG_DIR}/feature.log" 2>&1 &
echo "$!" >"${LOG_DIR}/feature.pid"
disown

KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" \
  POSTGRES_URL="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/traffic_platform" \
  nohup java -jar decision-engine/target/decision-engine-*.jar >"${LOG_DIR}/decision.log" 2>&1 &
echo "$!" >"${LOG_DIR}/decision.pid"
disown

if [[ ! -x "${LOG_DIR}/venv/bin/uvicorn" ]]; then
  python3 -m venv "${LOG_DIR}/venv"
  "${LOG_DIR}/venv/bin/pip" install -q -r ml-service/requirements.txt
fi
ENABLE_KAFKA_PIPELINE=true KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_HOST_PORT}" \
  nohup "${LOG_DIR}/venv/bin/uvicorn" app.main:app --app-dir ml-service --host 0.0.0.0 --port 8000 >"${LOG_DIR}/ml.log" 2>&1 &
echo "$!" >"${LOG_DIR}/ml.pid"
disown

wait_for_http "http://localhost:8080/actuator/health" "gateway-service"
wait_for_log "${LOG_DIR}/feature.log" "Started FeatureServiceApplication" "feature-service"
wait_for_log "${LOG_DIR}/decision.log" "Started DecisionEngineApplication" "decision-engine"
wait_for_http "http://localhost:8000/health" "ml-service"

# See tests/e2e/run-local-e2e.sh: "Started X Application" fires slightly
# before the Kafka consumer group has actually joined/gotten a partition
# assignment. Give it a moment before experiments start sending traffic.
sleep 5

log "Stack is up. gateway=:8080 feature-service(no HTTP) decision-engine(no HTTP) ml-service=:8000"
log "Kafka=:${KAFKA_HOST_PORT} Redis=:${REDIS_HOST_PORT} Postgres=:${POSTGRES_HOST_PORT}"
log "Logs: ${LOG_DIR}"
log "Run tests/chaos/scripts/stop-stack.sh when finished."
