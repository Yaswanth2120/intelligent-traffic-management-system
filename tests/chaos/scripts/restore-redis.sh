#!/usr/bin/env bash
set -euo pipefail

echo "Starting Redis container after outage drill"
docker compose -f infra/docker/docker-compose.yml start redis
echo "Redis started."
