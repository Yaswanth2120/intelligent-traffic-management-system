#!/usr/bin/env bash
set -euo pipefail

# Runs a single profile against an already-running local stack. The k6
# container reaches locally-run services through Docker Desktop's host alias.
# See docs/load-testing.md for startup and result-collection instructions.
if [[ $# -ne 2 ]]; then
  echo "usage: $0 <baseline|sustained|spike> <gateway|ml>" >&2
  exit 2
fi

PROFILE="$1"
TARGET="$2"
case "${PROFILE}" in baseline|sustained|spike) ;; *) exit 2 ;; esac
case "${TARGET}" in gateway|ml) ;; *) exit 2 ;; esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULT_DIR="${ROOT_DIR}/docs/results/load"
mkdir -p "${RESULT_DIR}"

TIMESTAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
RESULT_FILE="${RESULT_DIR}/${TIMESTAMP}-${PROFILE}-${TARGET}.json"
ROUTE="load-${PROFILE}-${TARGET}-${TIMESTAMP}"

docker run --rm --add-host=host.docker.internal:host-gateway \
  -v "${ROOT_DIR}:/work" -w /work \
  -e PROFILE="${PROFILE}" -e TARGET="${TARGET}" \
  -e BASE_URL="http://host.docker.internal" -e ROUTE="${ROUTE}" \
  grafana/k6:0.54.0 run --summary-export "/work/docs/results/load/$(basename "${RESULT_FILE}")" \
  tests/load/k6/performance.js

echo "Raw k6 summary: ${RESULT_FILE}"
