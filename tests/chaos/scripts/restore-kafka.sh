#!/usr/bin/env bash
# Restores the Kafka broker after simulate-kafka-outage.sh (or standalone).
# Idempotent: safe to run even if Kafka is already up.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "${SCRIPT_DIR}/lib.sh"

restore_start_ms=$(now_epoch_ms)
log "Restoring kafka (project=${COMPOSE_PROJECT_NAME})..."

if ! "${COMPOSE[@]}" start kafka; then
  fail "docker compose start kafka failed"
fi

if ! wait_for_tcp "${KAFKA_HOST_PORT}" "kafka" 60; then
  fail "kafka did not reopen port ${KAFKA_HOST_PORT} after restore"
fi

restore_end_ms=$(now_epoch_ms)
restore_ms=$((restore_end_ms - restore_start_ms))
log "kafka restored in ${restore_ms}ms"
echo "${restore_ms}"
