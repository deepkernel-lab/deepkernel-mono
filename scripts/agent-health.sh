#!/usr/bin/env bash
set -euo pipefail

# Ping the agent HTTP server /health (if enabled)
AGENT_HOST=${AGENT_HOST:-localhost}
AGENT_PORT=${AGENT_PORT:-8082}

echo "Checking agent health at http://${AGENT_HOST}:${AGENT_PORT}/health"
curl -s -D - "http://${AGENT_HOST}:${AGENT_PORT}/health"
echo

