# Local Kubernetes deployment

This repository was validated on a local `kind` cluster, not a public cloud
deployment. It uses only standard Kubernetes CPU HPA; no Prometheus Adapter,
KEDA, custom metrics, or forecast-driven scaling is installed.

## Commands

```bash
brew install kind kubectl
make k8s-up
make k8s-status
make k8s-down
```

`k8s-up` creates `traffic-platform`, installs Metrics Server and ingress-nginx,
builds the four service images locally, loads them into kind, generates local
Postgres/Grafana secrets without committing them, and deploys Kafka, Redis,
Postgres, services, Prometheus, Grafana, ingress, and CPU HPA.

For local browser access use port-forwarding:

```bash
kubectl -n traffic-platform port-forward svc/gateway-service 8080:8080
kubectl -n traffic-platform port-forward svc/prometheus 9090:9090
kubectl -n traffic-platform port-forward svc/grafana 3000:3000
```

Grafana is provisioned with the repository dashboard. Capture a screenshot by
opening `http://localhost:3000` after the port-forward; obtain the generated
local-only password from `make k8s-up` output. Do not reuse those credentials
outside local development.

For cloud/VPS, publish the four images to a private registry, replace the
local image names, provide `traffic-platform-secrets` through the platform's
secret manager, choose a production StorageClass, and install a managed
ingress controller/TLS certificate.
