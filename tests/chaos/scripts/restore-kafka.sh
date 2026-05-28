#!/usr/bin/env bash
set -euo pipefail

echo "Starting Kafka container after outage drill"
docker compose -f infra/docker/docker-compose.yml start kafka
echo "Kafka started."
