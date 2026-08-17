#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER_NAME="${KIND_CLUSTER_NAME:-traffic-platform}"
NAMESPACE="traffic-platform"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-$(openssl rand -hex 18)}"
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-$(openssl rand -hex 18)}"

if ! kind get clusters | grep -qx "${CLUSTER_NAME}"; then
  kind create cluster --name "${CLUSTER_NAME}" --wait 120s
fi
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.8.1/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' || true
kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s
kubectl label node "${CLUSTER_NAME}-control-plane" ingress-ready=true --overwrite
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.0/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=180s

cd "${ROOT_DIR}"
docker build -f gateway-service/Dockerfile -t traffic-platform/gateway-service:dev .
docker build -f feature-service/Dockerfile -t traffic-platform/feature-service:dev .
docker build -f decision-engine/Dockerfile -t traffic-platform/decision-engine:dev .
docker build -f ml-service/Dockerfile -t traffic-platform/ml-service:dev .
kind load docker-image --name "${CLUSTER_NAME}" traffic-platform/gateway-service:dev traffic-platform/feature-service:dev traffic-platform/decision-engine:dev traffic-platform/ml-service:dev

kubectl apply -f infra/k8s/namespace.yaml
kubectl -n "${NAMESPACE}" create secret generic traffic-platform-secrets \
  --from-literal=POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
  --from-literal=GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NAMESPACE}" create configmap prometheus-config --from-file=prometheus.yml=infra/k8s/prometheus.yml --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NAMESPACE}" create configmap postgres-init --from-file=init.sql=infra/docker/postgres/init.sql --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NAMESPACE}" create configmap grafana-provisioning-datasources --from-file=datasources.yaml=infra/docker/grafana/provisioning/datasources/prometheus.yml --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NAMESPACE}" create configmap grafana-provisioning-dashboards --from-file=dashboards.yaml=infra/docker/grafana/provisioning/dashboards/dashboards.yml --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NAMESPACE}" create configmap grafana-dashboards --from-file=traffic-platform-overview.json=infra/docker/grafana/dashboards/traffic-platform-overview.json --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f infra/k8s/configmap.yaml -f infra/k8s/platform-infra.yaml -f infra/k8s/gateway-service.yaml -f infra/k8s/feature-service.yaml -f infra/k8s/decision-engine.yaml -f infra/k8s/ml-service.yaml -f infra/k8s/autoscaling.yaml -f infra/k8s/prometheus-rbac.yaml -f infra/k8s/monitoring.yaml -f infra/k8s/ingress.yaml
for app in gateway-service feature-service decision-engine ml-service; do
  kubectl -n "${NAMESPACE}" set image "deployment/${app}" "${app}=traffic-platform/${app}:dev"
done
kubectl -n "${NAMESPACE}" rollout status deployment/postgres --timeout=180s
kubectl -n "${NAMESPACE}" rollout status deployment/redis --timeout=180s
kubectl -n "${NAMESPACE}" rollout status deployment/kafka --timeout=180s
for app in gateway-service feature-service decision-engine ml-service prometheus grafana; do kubectl -n "${NAMESPACE}" rollout status "deployment/${app}" --timeout=240s; done
echo "Grafana admin password (local only): ${GRAFANA_ADMIN_PASSWORD}"
