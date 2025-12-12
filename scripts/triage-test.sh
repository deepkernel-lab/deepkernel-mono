#!/usr/bin/env bash
set -euo pipefail

# Triage helper: triggers Gemini triage directly without agent.
#
# Usage:
#   GEMINI_API_KEY=... (set in server env, not here)
#   CONTAINER_ID=bachat-bank_backend_1 \
#   SYSCALL_SUMMARY=$'record_count=...\narg_class_counts=...\ntop_syscalls=...' \
#   DIFF_SUMMARY="Added outbound call to X" \
#   ./scripts/triage-test.sh | jq .

HOST=${HOST:-localhost}
PORT=${PORT:-9090} # DeepKernel server port (do not change without confirmation)

CONTAINER_ID=${CONTAINER_ID:-bachat-bank_backend_1}
ML_SCORE=${ML_SCORE:-0.95}
IS_ANOMALOUS=${IS_ANOMALOUS:-true}
SYSCALL_SUMMARY=${SYSCALL_SUMMARY:-"record_count=120\narg_class_counts=FILE=10 NET=80 PROC=10 MEM=0 OTHER=20\ntop_syscalls=connect:55, socket:20, open:10\nsequence_sample=socket → connect → connect"}
DIFF_SUMMARY=${DIFF_SUMMARY:-"Demo change context not set. Use scripts/set-change-context.sh to set a realistic diff summary."}

JSON="$(python3 - <<PY
import json, os
print(json.dumps({
  "container_id": os.environ["CONTAINER_ID"],
  "ml_score": float(os.environ.get("ML_SCORE","0.95")),
  "is_anomalous": os.environ.get("IS_ANOMALOUS","true").lower() in ("1","true","yes"),
  "syscall_summary": os.environ.get("SYSCALL_SUMMARY",""),
  "diff_summary": os.environ.get("DIFF_SUMMARY","")
}))
PY
)"

curl -sS -H "Content-Type: application/json" \
  -X POST \
  --data "${JSON}" \
  "http://${HOST}:${PORT}/api/ui/demo/triage"
echo
