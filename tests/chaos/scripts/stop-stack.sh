#!/usr/bin/env bash
# Deterministically tears down everything start-stack.sh started: the four
# local service processes, then the infra containers. Safe to run even if
# some/all of the stack is already stopped (idempotent).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "${SCRIPT_DIR}/lib.sh"

for name in gateway feature decision ml; do
  pid_file="${LOG_DIR}/${name}.pid"
  if [[ -f "${pid_file}" ]]; then
    pid="$(cat "${pid_file}")"
    if kill -0 "${pid}" 2>/dev/null; then
      log "Stopping ${name} (pid ${pid})"
      kill "${pid}" 2>/dev/null || true
      for _ in $(seq 1 15); do
        kill -0 "${pid}" 2>/dev/null || break
        sleep 1
      done
      kill -9 "${pid}" 2>/dev/null || true
    fi
    rm -f "${pid_file}"
  fi
done

log "Stopping infra containers (project=${COMPOSE_PROJECT_NAME})..."
"${COMPOSE[@]}" down >/dev/null 2>&1 || true

log "Stack stopped."
