#!/usr/bin/env bash
set -euo pipefail

COUNT=${COUNT:-10}           # how many iterations
BASE_URL=${BASE_URL:-http://localhost:8000}
USER=${USER:-demo}
PASS=${PASS:-demo}

echo "[*] Logging in to get token from ${BASE_URL}/login"
TOKEN=$(curl -s -X POST "${BASE_URL}/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" | jq -r '.token')

if [[ -z "${TOKEN}" || "${TOKEN}" == "null" ]]; then
  echo "[!] Failed to obtain token"; exit 1
fi
echo "[*] Got token: ${TOKEN}"

for i in $(seq 1 "${COUNT}"); do
  echo "[*] Iteration ${i}"
  curl -s "${BASE_URL}/health" && echo
  curl -s "${BASE_URL}/account" -H "Authorization: Bearer ${TOKEN}" && echo
  sleep 1
done
