#!/usr/bin/env bash
set -euo pipefail

# Context helper: manually set the "CI/CD change context" that the server will attach to LLM triage.
#
# Usage:
#   CONTAINER_ID=bachat-bank_backend_1 COMMIT_ID=deadbeef REPO_URL=https://github.com/... \
#   DIFF_SUMMARY="Made outbound call to unknown host" \
#   ./scripts/set-change-context.sh
#
# Or provide a file:
#   DIFF_FILE=./demo-change-summary.txt ./scripts/set-change-context.sh

HOST=${HOST:-localhost}
PORT=${PORT:-9090} # DeepKernel server port (do not change without confirmation)

CONTAINER_ID=${CONTAINER_ID:-bachat-bank_backend_1}
COMMIT_ID=${COMMIT_ID:-demo-commit}
REPO_URL=${REPO_URL:-demo}
CHANGED_FILES=${CHANGED_FILES:-backend/main.py}

DIFF_SUMMARY=${DIFF_SUMMARY:-}
DIFF_FILE=${DIFF_FILE:-}

if [[ -n "${DIFF_FILE}" ]]; then
  if [[ ! -f "${DIFF_FILE}" ]]; then
    echo "DIFF_FILE not found: ${DIFF_FILE}" >&2
    exit 1
  fi
  DIFF_SUMMARY="$(cat "${DIFF_FILE}")"
fi

if [[ -z "${DIFF_SUMMARY}" ]]; then
  echo "Provide DIFF_SUMMARY=<text> or DIFF_FILE=<path>." >&2
  exit 1
fi

JSON="$(python3 - <<PY
import json, os
container_id=os.environ["CONTAINER_ID"]
commit_id=os.environ["COMMIT_ID"]
repo_url=os.environ["REPO_URL"]
changed_files=os.environ.get("CHANGED_FILES","").split(",")
changed_files=[f.strip() for f in changed_files if f.strip()]
diff_summary=os.environ["DIFF_SUMMARY"]
print(json.dumps({
  "container_id": container_id,
  "commit_id": commit_id,
  "repo_url": repo_url,
  "changed_files": changed_files,
  "diff_summary": diff_summary
}))
PY
)"

echo "[change-context] POST http://${HOST}:${PORT}/api/ui/demo/change-context"
curl -sS -H "Content-Type: application/json" \
  -X POST \
  --data "${JSON}" \
  "http://${HOST}:${PORT}/api/ui/demo/change-context"
echo
