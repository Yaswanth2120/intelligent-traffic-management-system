#!/usr/bin/env bash
# Restores Redis after simulate-redis-outage.sh (or standalone). Idempotent:
# safe to run even if Redis is already up.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "${SCRIPT_DIR}/lib.sh"

restore_start_ms=$(now_epoch_ms)
log "Restoring redis (project=${COMPOSE_PROJECT_NAME})..."

if ! "${COMPOSE[@]}" start redis; then
  fail "docker compose start redis failed"
fi

if ! wait_for_tcp "${REDIS_HOST_PORT}" "redis" 60; then
  fail "redis did not reopen port ${REDIS_HOST_PORT} after restore"
fi

restore_end_ms=$(now_epoch_ms)
restore_ms=$((restore_end_ms - restore_start_ms))
log "redis restored in ${restore_ms}ms"
echo "${restore_ms}"
