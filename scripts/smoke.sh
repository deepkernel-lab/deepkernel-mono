#!/usr/bin/env bash
set -euo pipefail

HOST=${HOST:-localhost}
PORT=${PORT:-9090}

echo "[smoke] sending sample window"
HOST=${HOST} PORT=${PORT} ./scripts/mock-agent-send.sh

echo "[smoke] fetching containers"
curl -s "http://${HOST}:${PORT}/api/ui/containers" | jq .

echo "[smoke] fetching events"
curl -s "http://${HOST}:${PORT}/api/ui/events?limit=5" | jq .

