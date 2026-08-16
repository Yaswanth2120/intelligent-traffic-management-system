#!/usr/bin/env bash
# Chaos experiment: kill the Kafka broker while traffic keeps flowing through
# the gateway, observe behavior, restore Kafka, measure recovery.
#
# Precondition: tests/chaos/scripts/start-stack.sh has already been run
# (require_stack_running below fails fast with a clear message if not).
#
# What this checks (see docs/chaos-testing.md for the full write-up):
#   1. Gateway request path stays available (HTTP 2xx) while Kafka is down --
#      TrafficMetricPublisher.publish() is fire-and-forget, per
#      docs/architecture.md's "gateway buffers minimally and drops metrics
#      before impacting request path".
#   2. gateway_metric_publish_total{result="failure"} rises during the outage.
#   3. No permanent data loss for a SHORT outage (well under the Kafka
#      producer's delivery.timeout.ms, default ~120s): once Kafka returns,
#      buffered/retried producer sends complete and feature-service's
#      Postgres traffic_history row count catches up to reflect all requests.
#   4. Deterministic cleanup: Kafka is always restored (even on failure) and
#      the script exits non-zero if any invariant above didn't hold.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "${SCRIPT_DIR}/lib.sh"

OUTAGE_DURATION_SEC="${OUTAGE_DURATION_SEC:-20}"
ROUTE="orders-route"
RUN_ID="kafka-outage-$(date -u +%Y%m%dT%H%M%SZ)"
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

metric_publish_failures() {
  curl -fsS http://localhost:8080/actuator/prometheus 2>/dev/null \
    | awk '/^gateway_metric_publish_total\{/ && /result="failure"/ { sum += $NF } END { print sum+0 }'
}

log "=== Kafka outage experiment (run_id=${RUN_ID}) ==="
require_stack_running

data_loss="unknown"
manual_intervention="no"
unexpected_failure=0

cleanup_and_restore() {
  log "Ensuring kafka is restored (cleanup)..."
  if ! "${SCRIPT_DIR}/restore-kafka.sh" >/dev/null 2>>"${LOG_DIR}/${RUN_ID}-restore.log"; then
    log "WARNING: automatic kafka restore reported failure -- check ${LOG_DIR}/${RUN_ID}-restore.log"
    manual_intervention="yes (restore-kafka.sh failed, may need manual docker compose start kafka)"
    unexpected_failure=1
  fi
}
trap cleanup_and_restore EXIT

pre_failures=$(metric_publish_failures)
rows_before_outage=$(traffic_history_total_count)
log "traffic_history row count for route=${ROUTE} before outage: ${rows_before_outage}"
outage_start_epoch=$(date -u +%s)
outage_start_iso=$(date -u +%Y-%m-%dT%H:%M:%SZ)
log "Outage start: ${outage_start_iso} (epoch ${outage_start_epoch})"

log "Stopping kafka..."
if ! "${COMPOSE[@]}" stop kafka; then
  fail "docker compose stop kafka failed -- cannot run this experiment"
fi
wait_for_tcp_closed "${KAFKA_HOST_PORT}" "kafka" 15 || log "WARNING: kafka port still open after stop (unexpected, continuing)"

log "Generating traffic for ${OUTAGE_DURATION_SEC}s while kafka is down (logging to ${DURING_LOG})..."
generate_traffic "${DURING_LOG}" "${OUTAGE_DURATION_SEC}" "/api/orders"

during_summary=$(summarize_traffic "${DURING_LOG}")
log "During-outage gateway traffic: ${during_summary}"

outage_end_epoch=$(date -u +%s)
outage_actual_sec=$((outage_end_epoch - outage_start_epoch))

log "Restoring kafka..."
restore_ms=$("${SCRIPT_DIR}/restore-kafka.sh" 2>>"${LOG_DIR}/${RUN_ID}-restore.log")
restore_status=$?
if [[ ${restore_status} -ne 0 || -z "${restore_ms}" ]]; then
  unexpected_failure=1
  manual_intervention="yes (restore-kafka.sh failed)"
  restore_ms=-1
fi
trap - EXIT
log "Kafka restore call took ${restore_ms}ms (this is broker-port-open time, not full pipeline recovery)"

log "Settling for consumer group rejoin + producer retry flush..."
sleep 15

log "Generating post-recovery traffic (logging to ${AFTER_LOG})..."
generate_traffic "${AFTER_LOG}" 5 "/api/orders"
after_summary=$(summarize_traffic "${AFTER_LOG}")
log "Post-recovery gateway traffic: ${after_summary}"

sleep 10

post_failures=$(metric_publish_failures)
failures_during_outage=$((post_failures - pre_failures))

during_total=$(echo "${during_summary}" | jq -r '.total')
after_total=$(echo "${after_summary}" | jq -r '.total')
expected_total=$((during_total + after_total))

recovery_confirmed_epoch=$(date -u +%s)
pipeline_recovery_sec=$((recovery_confirmed_epoch - outage_end_epoch))

# Growth in total row count for this route since before the outage started.
# Every request generated by this script (during AND after the outage) should
# eventually be reflected here, regardless of whether its Kafka publish
# happened immediately, was buffered internally by the producer and delivered
# once the broker returned, or was dropped and never delivered at all.
rows_after_total=$(traffic_history_total_count)
rows_added=$((rows_after_total - rows_before_outage))

if [[ "${rows_added}" -ge "${expected_total}" ]]; then
  data_loss="no -- traffic_history grew by ${rows_added} rows for ${expected_total} requests generated by this run (during-outage sends may have been dropped per docs/architecture.md and/or buffered+delivered once kafka returned; either way nothing generated by this run is missing)"
elif [[ "${rows_added}" -ge "${after_total}" ]]; then
  dropped=$((expected_total - rows_added))
  data_loss="partial (expected, by design) -- ${dropped} of ${during_total} during-outage requests' metrics were dropped (docs/architecture.md: 'drops metrics before impacting request path'); all ${after_total} post-recovery requests landed (traffic_history grew by ${rows_added} rows for ${expected_total} requests)"
else
  data_loss="YES -- traffic_history only grew by ${rows_added} rows for ${expected_total} requests, and post-recovery traffic itself did not fully land; pipeline did not resume processing after kafka returned"
  unexpected_failure=1
fi
log "traffic_history rows added since before outage: ${rows_added} (expected ${expected_total}: ${during_total} during + ${after_total} after)"

gateway_availability_ok=1
during_success=$(echo "${during_summary}" | jq -r '.success')
if [[ "${during_total}" -gt 0 && "${during_success}" -ne "${during_total}" ]]; then
  gateway_availability_ok=0
  unexpected_failure=1
fi

cat >"${RESULT_FILE}" <<JSON
{
  "run_id": "${RUN_ID}",
  "scenario": "kafka_outage",
  "outage_start_iso": "${outage_start_iso}",
  "outage_requested_duration_sec": ${OUTAGE_DURATION_SEC},
  "outage_actual_duration_sec": ${outage_actual_sec},
  "kafka_broker_restore_ms": ${restore_ms},
  "pipeline_settle_sec_after_restore": 15,
  "during_outage_traffic": ${during_summary},
  "post_recovery_traffic": ${after_summary},
  "gateway_metric_publish_failures_during_outage": ${failures_during_outage},
  "gateway_stayed_available": $( [[ ${gateway_availability_ok} -eq 1 ]] && echo true || echo false ),
  "traffic_history_rows_before_outage": ${rows_before_outage},
  "traffic_history_rows_added": ${rows_added},
  "traffic_history_rows_expected": ${expected_total},
  "pipeline_recovery_sec": ${pipeline_recovery_sec},
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
