#!/usr/bin/env bash
set -euo pipefail

HOST=${HOST:-localhost}
PORT=${PORT:-9090}
CONTAINER_ID=${CONTAINER_ID:-demo/container}
AGENT_ID=${AGENT_ID:-agent-1}
START_TS=${START_TS:-1000000}

cat > /tmp/dk_window.json <<'EOF'
{
  "version": 1,
  "agent_id": "__AGENT_ID__",
  "container_id": "__CONTAINER_ID__",
  "window_start_ts_ns": __START_TS__,
  "records": [
    { "delta_ts_us": 0, "syscall_id": 59, "arg_class": 2, "arg_bucket": 1 },
    { "delta_ts_us": 100, "syscall_id": 2, "arg_class": 0, "arg_bucket": 0 }
  ]
}
EOF

sed -i "s#__AGENT_ID__#${AGENT_ID}#g" /tmp/dk_window.json
sed -i "s#__CONTAINER_ID__#${CONTAINER_ID}#g" /tmp/dk_window.json
sed -i "s#__START_TS__#${START_TS}#g" /tmp/dk_window.json

echo "Posting window to http://${HOST}:${PORT}/api/v1/agent/windows"
curl -s -D - -H "Content-Type: application/json" -X POST --data @/tmp/dk_window.json "http://${HOST}:${PORT}/api/v1/agent/windows"
echo

