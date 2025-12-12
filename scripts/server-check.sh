#!/usr/bin/env bash
set -euo pipefail

HOST=${HOST:-localhost}
PORT=${PORT:-9090}

echo "[server-check] send sample window"
HOST=${HOST} PORT=${PORT} ./scripts/mock-agent-send.sh

echo "[server-check] containers"
curl -s "http://${HOST}:${PORT}/api/ui/containers" | jq .

CID=$(curl -s "http://${HOST}:${PORT}/api/ui/containers" | jq -r '.[0].id // empty')
if [[ -n "$CID" ]]; then
  echo "[server-check] models for ${CID}"
  curl -s "http://${HOST}:${PORT}/api/ui/containers/${CID}/models" | jq .
else
  echo "[server-check] no containers returned"
fi

echo "[server-check] events (5)"
curl -s "http://${HOST}:${PORT}/api/ui/events?limit=5" | jq .

