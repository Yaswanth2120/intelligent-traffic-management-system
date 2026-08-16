#!/usr/bin/env bash
# Chaos experiment: kill Redis while traffic keeps flowing through the
# gateway, observe behavior, restore Redis, measure recovery.
#
# Precondition: tests/chaos/scripts/start-stack.sh has already been run
# (require_stack_running below fails fast with a clear message if not).
#
# What this checks (see docs/chaos-testing.md for the full write-up):
#   1. Gateway request path is unaffected -- Redis is only touched by
#      feature-service, not the gateway's own request path.
#   2. Per docs/architecture.md: "Redis unavailable: feature service
#      continues durable writes to PostgreSQL and rebuilds hot state later."
#      This is checked directly: does feature-service's Postgres
#      traffic_history row count keep growing WHILE Redis is down?
#   3. After Redis returns, does the hot-cache key (traffic_window:<route>)
#      get rebuilt by the next event (state recovers, no manual intervention)?
#   4. Deterministic cleanup: Redis is always restored (even on failure) and
#      the script exits non-zero if any invariant above didn't hold.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "${SCRIPT_DIR}/lib.sh"

OUTAGE_DURATION_SEC="${OUTAGE_DURATION_SEC:-20}"
ROUTE="orders-route"
RUN_ID="redis-outage-$(date -u +%Y%m%dT%H%M%SZ)"
DURING_LOG="${LOG_DIR}/${RUN_ID}-during.csv"
AFTER_LOG="${LOG_DIR}/${RUN_ID}-after.csv"
RESULT_FILE="${RESULTS_DIR}/${RUN_ID}.json"
: >"${DURING_LOG}"
: >"${AFTER_LOG}"

PSQL=(psql "postgresql://traffic_user:traffic_pass@localhost:${POSTGRES_HOST_PORT}/traffic_platform" -t -A -c)

# traffic_history.timestamp is the aggregation WINDOW START (the oldest
# event still in feature-service's 60s sliding window), not a row-insert
# timestamp -- under continuous traffic it lags behind wall-clock time, so
# filtering "timestamp >= outage_start_epoch" silently undercounts. A simple
# total-row-count delta (before vs after) has no such gap.
traffic_history_total_count() {
  "${PSQL[@]}" "select count(*) from traffic_history where route = '${ROUTE}';" 2>/dev/null | tr -d '[:space:]'
}

redis_get() {
  "${COMPOSE[@]}" exec -T redis redis-cli GET "traffic_window:${ROUTE}" 2>/dev/null | tr -d '\r'
}

log "=== Redis outage experiment (run_id=${RUN_ID}) ==="
require_stack_running

data_loss="unknown"
manual_intervention="no"
unexpected_failure=0

cleanup_and_restore() {
  log "Ensuring redis is restored (cleanup)..."
  if ! "${SCRIPT_DIR}/restore-redis.sh" >/dev/null 2>>"${LOG_DIR}/${RUN_ID}-restore.log"; then
    log "WARNING: automatic redis restore reported failure -- check ${LOG_DIR}/${RUN_ID}-restore.log"
    manual_intervention="yes (restore-redis.sh failed, may need manual docker compose start redis)"
    unexpected_failure=1
  fi
}
trap cleanup_and_restore EXIT

baseline_redis_value=$(redis_get || true)
log "Baseline traffic_window:${ROUTE} in Redis before outage: ${baseline_redis_value:-<none>}"
rows_before_outage=$(traffic_history_total_count)
log "traffic_history row count for route=${ROUTE} before outage: ${rows_before_outage}"

outage_start_epoch=$(date -u +%s)
outage_start_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)
log "Outage start: ${outage_start_iso} (epoch ${outage_start_epoch})"

log "Stopping redis..."
if ! "${COMPOSE[@]}" stop redis; then
  fail "docker compose stop redis failed -- cannot run this experiment"
fi
wait_for_tcp_closed "${REDIS_HOST_PORT}" "redis" 15 || log "WARNING: redis port still open after stop (unexpected, continuing)"

log "Generating traffic for ${OUTAGE_DURATION_SEC}s while redis is down (logging to ${DURING_LOG})..."
generate_traffic "${DURING_LOG}" "${OUTAGE_DURATION_SEC}" "/api/orders"

during_summary=$(summarize_traffic "${DURING_LOG}")
log "During-outage gateway traffic: ${during_summary}"

log "Waiting for feature-service to finish processing in-flight events (single-threaded consumption, so this can take a bit if every event hits the Redis failover timeout)..."
during_total_for_poll=$(echo "${during_summary}" | jq -r '.total')
rows_during_outage_snapshot=$(poll_until_stable \
  "psql \"postgresql://traffic_user:traffic_pass@localhost:${POSTGRES_HOST_PORT}/traffic_platform\" -t -A -c \"select count(*) from traffic_history where route = '${ROUTE}';\"" \
  "$((rows_before_outage + during_total_for_poll))" 60 3)
rows_added_during_outage=$((rows_during_outage_snapshot - rows_before_outage))
log "traffic_history rows added while redis was down: ${rows_added_during_outage} (total now ${rows_during_outage_snapshot})"

outage_end_epoch=$(date -u +%s)

log "Restoring redis..."
restore_ms=$("${SCRIPT_DIR}/restore-redis.sh" 2>>"${LOG_DIR}/${RUN_ID}-restore.log")
restore_status=$?
if [[ ${restore_status} -ne 0 || -z "${restore_ms}" ]]; then
  unexpected_failure=1
  manual_intervention="yes (restore-redis.sh failed)"
  restore_ms=-1
fi
trap - EXIT
log "Redis restore call took ${restore_ms}ms"

log "Sending a recovery probe request so feature-service rebuilds the Redis key..."
sleep 3
generate_traffic "${AFTER_LOG}" 3 "/api/orders"
after_summary=$(summarize_traffic "${AFTER_LOG}")
log "Post-recovery gateway traffic: ${after_summary}"

after_total_for_poll=$(echo "${after_summary}" | jq -r '.total')
rows_after_recovery_total=$(poll_until_stable \
  "psql \"postgresql://traffic_user:traffic_pass@localhost:${POSTGRES_HOST_PORT}/traffic_platform\" -t -A -c \"select count(*) from traffic_history where route = '${ROUTE}';\"" \
  "$((rows_during_outage_snapshot + after_total_for_poll))" 30 3)
recovered_redis_value=$(redis_get || true)
log "traffic_window:${ROUTE} in Redis after recovery: ${recovered_redis_value:-<none>}"

rows_added_after_recovery=$((rows_after_recovery_total - rows_during_outage_snapshot))
log "traffic_history rows added after redis recovery: ${rows_added_after_recovery} (total now ${rows_after_recovery_total})"

during_total=$(echo "${during_summary}" | jq -r '.total')
after_total=$(echo "${after_summary}" | jq -r '.total')

gateway_availability_ok=1
during_success=$(echo "${during_summary}" | jq -r '.success')
if [[ "${during_total}" -gt 0 && "${during_success}" -ne "${during_total}" ]]; then
  gateway_availability_ok=0
  unexpected_failure=1
fi

postgres_writes_continued_during_outage=0
if [[ "${rows_added_during_outage}" -ge "${during_total}" ]]; then
  postgres_writes_continued_during_outage=1
else
  unexpected_failure=1
fi

redis_state_rebuilt=0
if [[ -n "${recovered_redis_value}" ]]; then
  redis_state_rebuilt=1
else
  unexpected_failure=1
fi

if [[ "${postgres_writes_continued_during_outage}" -eq 1 ]]; then
  data_loss="no (traffic_history grew by ${rows_added_during_outage} rows for ${during_total} requests sent while redis was down, and by ${rows_added_after_recovery} rows for ${after_total} post-recovery requests)"
else
  data_loss="YES (traffic_history grew by only ${rows_added_during_outage} rows for ${during_total} requests sent while redis was down; the aggregate did not reliably reach Postgres, though only Redis was supposed to be affected per docs/architecture.md)"
fi

cat >"${RESULT_FILE}" <<JSON
{
  "run_id": "${RUN_ID}",
  "scenario": "redis_outage",
  "outage_start_iso": "${outage_start_iso}",
  "outage_requested_duration_sec": ${OUTAGE_DURATION_SEC},
  "redis_restore_ms": ${restore_ms},
  "during_outage_traffic": ${during_summary},
  "post_recovery_traffic": ${after_summary},
  "gateway_stayed_available": $( [[ ${gateway_availability_ok} -eq 1 ]] && echo true || echo false ),
  "traffic_history_rows_before_outage": ${rows_before_outage},
  "traffic_history_rows_added_during_outage": ${rows_added_during_outage},
  "traffic_history_rows_added_after_recovery": ${rows_added_after_recovery},
  "postgres_writes_continued_during_outage": $( [[ ${postgres_writes_continued_during_outage} -eq 1 ]] && echo true || echo false ),
  "redis_key_before_outage": $(printf '%s' "${baseline_redis_value:-}" | jq -Rs 'if length == 0 then null else . end'),
  "redis_key_after_recovery": $(printf '%s' "${recovered_redis_value:-}" | jq -Rs 'if length == 0 then null else . end'),
  "redis_state_rebuilt_after_recovery": $( [[ ${redis_state_rebuilt} -eq 1 ]] && echo true || echo false ),
  "data_loss": "${data_loss}",
  "manual_intervention_required": "${manual_intervention}",
  "unexpected_failure": $( [[ ${unexpected_failure} -eq 1 ]] && echo true || echo false )
}
JSON

log "Result written to ${RESULT_FILE}"
cat "${RESULT_FILE}" >&2

if [[ ${unexpected_failure} -eq 1 ]]; then
  log "Experiment completed with UNEXPECTED FAILURE(S) -- see result file."
  exit 1
fi

log "Experiment completed. No unexpected failures observed."
exit 0
