#!/usr/bin/env bash
# Runs a k6 profile as a Job INSIDE the kind cluster, hitting gateway-service
# (or ml-service) over in-cluster DNS instead of through kubectl port-forward.
#
# Why: kubectl port-forward is a single tunnel not designed for load-test
# concurrency. Driving the autoscaling.md spike experiment through it produced
# a 47% client-side failure rate and p95 > 4s that came entirely from the
# tunnel, not the gateway -- confirmed by cross-checking Prometheus, which
# showed the gateway's own metrics healthy throughout. Running k6 as a Job in
# the same cluster/namespace removes that bottleneck.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <baseline|sustained|spike> <gateway|ml>" >&2
  exit 2
fi

PROFILE="$1"
TARGET="$2"
case "${PROFILE}" in baseline|sustained|spike) ;; *) exit 2 ;; esac
case "${TARGET}" in gateway|ml) ;; *) exit 2 ;; esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAMESPACE="traffic-platform"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
K8S_NAME_SUFFIX="$(date -u +%Y%m%d%H%M%S)" # k8s object names must be lowercase RFC 1123
JOB_NAME="k6-${PROFILE}-${TARGET}-${K8S_NAME_SUFFIX}"
RESULT_DIR="${ROOT_DIR}/docs/results/autoscaling"
mkdir -p "${RESULT_DIR}"
RESULT_FILE="${RESULT_DIR}/${TIMESTAMP}-${PROFILE}-${TARGET}-incluster.json"

BASE_URL="http://gateway-service"
[[ "${TARGET}" == "ml" ]] && BASE_URL="http://ml-service"

CONFIGMAP_NAME="k6-script-${K8S_NAME_SUFFIX}"
kubectl -n "${NAMESPACE}" create configmap "${CONFIGMAP_NAME}" \
  --from-file=performance.js="${ROOT_DIR}/tests/load/k6/performance.js" \
  --dry-run=client -o yaml | kubectl apply -f -

cat <<EOF | kubectl apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: ${JOB_NAME}
  namespace: ${NAMESPACE}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 600
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:0.54.0
          command: ["sh", "-c"]
          args:
            - |
              k6 run --summary-export /results/summary.json /scripts/performance.js
              echo "===SUMMARY_JSON_START==="
              cat /results/summary.json
              echo
              echo "===SUMMARY_JSON_END==="
          env:
            - {name: PROFILE, value: "${PROFILE}"}
            - {name: TARGET, value: "${TARGET}"}
            - {name: BASE_URL, value: "${BASE_URL}"}
            - {name: ROUTE, value: "autoscaling-${PROFILE}-${TARGET}-${K8S_NAME_SUFFIX}"}
          volumeMounts:
            - {name: script, mountPath: /scripts}
            - {name: results, mountPath: /results}
      volumes:
        - name: script
          configMap: {name: "${CONFIGMAP_NAME}"}
        - name: results
          emptyDir: {}
EOF

echo "Waiting for job/${JOB_NAME} to complete..." >&2
kubectl -n "${NAMESPACE}" wait --for=condition=complete "job/${JOB_NAME}" --timeout=600s || {
  echo "Job did not complete successfully; dumping logs:" >&2
  kubectl -n "${NAMESPACE}" logs "job/${JOB_NAME}" >&2
  exit 1
}

# Small delay + retry: reading logs from a just-completed pod can briefly
# race the log buffer flushing (observed once as truncated JSON output).
for attempt in 1 2 3 4 5; do
  sleep 2
  kubectl -n "${NAMESPACE}" logs "job/${JOB_NAME}" \
    | sed -n '/===SUMMARY_JSON_START===/,/===SUMMARY_JSON_END===/p' \
    | sed '1d;$d' > "${RESULT_FILE}"
  if python3 -c "import json; json.load(open('${RESULT_FILE}'))" 2>/dev/null; then
    break
  fi
  echo "Attempt ${attempt}: summary JSON incomplete, retrying..." >&2
done

echo "Raw k6 summary: ${RESULT_FILE}"
