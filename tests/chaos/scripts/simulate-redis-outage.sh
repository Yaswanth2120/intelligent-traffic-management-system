#!/usr/bin/env bash
set -euo pipefail

echo "Stopping Redis container to simulate cache outage"
docker compose -f infra/docker/docker-compose.yml stop redis
echo "Redis stopped. Observe feature_storage_operations_total for redis failures."
