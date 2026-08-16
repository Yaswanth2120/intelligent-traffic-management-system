#!/usr/bin/env bash
# Shared helpers for the chaos experiment scripts. Sourced, not executed.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CHAOS_DIR="${ROOT_DIR}/target/chaos"
LOG_DIR="${CHAOS_DIR}/logs"
RESULTS_DIR="${CHAOS_DIR}/results"
mkdir -p "${LOG_DIR}" "${RESULTS_DIR}"

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-traffic-platform-chaos}"
export KAFKA_HOST_PORT="${KAFKA_HOST_PORT:-29092}"
export REDIS_HOST_PORT="${REDIS_HOST_PORT:-26379}"
export POSTGRES_HOST_PORT="${POSTGRES_HOST_PORT:-25432}"
export PROMETHEUS_HOST_PORT="${PROMETHEUS_HOST_PORT:-29090}"
export GRAFANA_HOST_PORT="${GRAFANA_HOST_PORT:-23000}"

COMPOSE_FILE="${ROOT_DIR}/infra/docker/docker-compose.yml"
COMPOSE=(docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT_NAME}")

log() {
  # stderr, not stdout: several callers (e.g. restore-kafka.sh /
  # restore-redis.sh) capture a script's stdout via `$(...)` to get a single
  # machine-readable value back, and log lines must not pollute that.
  echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*" >&2
}

fail() {
  log "FAIL: $*"
  exit 1
}

now_epoch_ms() {
  # macOS `date` has no %N; python3 is always present via ml-service tooling.
  python3 -c 'import time; print(int(time.time() * 1000))'
}

wait_for_tcp() {
  local port="$1" name="$2" attempts="${3:-60}"
  for _ in $(seq 1 "${attempts}"); do
    if (echo >"/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1; then
      log "${name} port ${port} is open"
      return 0
    fi
    sleep 1
  done
  log "${name} did not open port ${port} within ${attempts}s"
  return 1
}

wait_for_tcp_closed() {
  local port="$1" name="$2" attempts="${3:-30}"
  for _ in $(seq 1 "${attempts}"); do
    if ! (echo >"/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1; then
      log "${name} port ${port} is closed"
      return 0
    fi
    sleep 1
  done
  log "${name} port ${port} did not close within ${attempts}s"
  return 1
}

wait_for_http() {
  local url="$1" name="$2" attempts="${3:-60}"
  for _ in $(seq 1 "${attempts}"); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      log "${name} is ready (${url})"
      return 0
    fi
    sleep 1
  done
  log "${name} did not become ready at ${url} within ${attempts}s"
  return 1
}

wait_for_log() {
  local log_file="$1" pattern="$2" name="$3" attempts="${4:-60}"
  for _ in $(seq 1 "${attempts}"); do
    if [[ -f "${log_file}" ]] && grep -q "${pattern}" "${log_file}" 2>/dev/null; then
      log "${name} is ready"
      return 0
    fi
    sleep 1
  done
  log "${name} did not log '${pattern}' in ${log_file} within ${attempts}s"
  return 1
}

# Precondition all experiments share: the stack must already be up (started
# via start-stack.sh). Fails fast with a clear message instead of a confusing
# mid-experiment error if it isn't.
require_stack_running() {
  if ! curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
    fail "gateway-service is not reachable on :8080. Run tests/chaos/scripts/start-stack.sh first."
  fi
  if [[ ! -f "${LOG_DIR}/feature.pid" ]] || ! kill -0 "$(cat "${LOG_DIR}/feature.pid")" 2>/dev/null; then
    fail "feature-service is not running (no live PID in ${LOG_DIR}/feature.pid). Run tests/chaos/scripts/start-stack.sh first."
  fi
  if ! "${COMPOSE[@]}" ps kafka 2>/dev/null | grep -q "Up"; then
    fail "kafka container is not running under compose project ${COMPOSE_PROJECT_NAME}. Run tests/chaos/scripts/start-stack.sh first."
  fi
  if ! "${COMPOSE[@]}" ps redis 2>/dev/null | grep -q "Up"; then
    fail "redis container is not running under compose project ${COMPOSE_PROJECT_NAME}. Run tests/chaos/scripts/start-stack.sh first."
  fi
}

# Sends `count` requests through the gateway at roughly 1/sec, appending
# "<epoch_ms>,<http_status>,<latency_ms>" rows to `out_file`. Meant to be run
# in the background during an outage window so we have a real, timestamped
# record of gateway behavior while the dependency is down, not a guess.
generate_traffic() {
  local out_file="$1" count="$2" route="${3:-/api/orders}"
  local i status start_ms end_ms
  for ((i = 0; i < count; i++)); do
    start_ms=$(now_epoch_ms)
    status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
      -H "X-Client-Id: chaos-client" "http://localhost:8080${route}" || echo "000")
    end_ms=$(now_epoch_ms)
    echo "${start_ms},${status},$((end_ms - start_ms))" >>"${out_file}"
    sleep 1
  done
}

# Polls `check_cmd` (a shell command printing an integer) until its output
# reaches `target` or stops changing across `stable_checks` consecutive
# polls, or `max_wait_sec` elapses. Used instead of a fixed sleep to settle
# feature-service processing: a single event's worst-case Redis/Kafka
# failover latency is bounded (see application.yml timeouts) but consumption
# is single-threaded, so N queued events can take a while in the worst case
# -- polling avoids both "declared done too early" and "waited too long".
poll_until_stable() {
  local check_cmd="$1" target="$2" max_wait_sec="${3:-60}" stable_checks="${4:-3}"
  local last_value="" stable_count=0 waited=0
  while [[ ${waited} -lt ${max_wait_sec} ]]; do
    local value
    value=$(eval "${check_cmd}" 2>/dev/null | tr -d '[:space:]')
    if [[ "${value}" =~ ^[0-9]+$ ]] && [[ "${value}" -ge "${target}" ]]; then
      echo "${value}"
      return 0
    fi
    if [[ "${value}" == "${last_value}" ]]; then
      stable_count=$((stable_count + 1))
      if [[ ${stable_count} -ge ${stable_checks} ]]; then
        echo "${value}"
        return 0
      fi
    else
      stable_count=0
    fi
    last_value="${value}"
    sleep 2
    waited=$((waited + 2))
  done
  echo "${last_value}"
  return 0
}

# Summarizes a generate_traffic log: total, success (2xx), error (non-2xx or
# 000 for connect failure), and average latency in ms.
summarize_traffic() {
  local file="$1"
  if [[ ! -s "${file}" ]]; then
    echo '{"total":0,"success":0,"error":0,"avg_latency_ms":0}'
    return
  fi
  awk -F, '
    { total++; lat_sum += $3; if ($2 ~ /^2/) { success++ } else { error++ } }
    END {
      printf "{\"total\":%d,\"success\":%d,\"error\":%d,\"avg_latency_ms\":%.1f}",
        total, success+0, error+0, (total > 0 ? lat_sum/total : 0)
    }
  ' "${file}"
}
