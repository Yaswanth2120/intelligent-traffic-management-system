#!/usr/bin/env bash
set -euo pipefail

echo "Stopping Kafka container to simulate broker outage"
docker compose -f infra/docker/docker-compose.yml stop kafka
echo "Kafka stopped. Observe gateway_metric_publish_total and decision/feature publish failures."
