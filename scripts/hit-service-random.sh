#!/usr/bin/env bash
set -euo pipefail

COUNT=${COUNT:-20}                 # total calls
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
  # pick a call type at random: 0=health, 1=account with token, 2=account without token
  pick=$((RANDOM % 3))
  printf "\n[*] Iteration %s -> " "$i"
  case $pick in
    0)
      echo "GET /health"
      curl -s "${BASE_URL}/health" && echo
      ;;
    1)
      echo "GET /account (with token)"
      curl -s "${BASE_URL}/account" -H "Authorization: Bearer ${TOKEN}" && echo
      ;;
    2)
      echo "GET /account (no token)"
      curl -s "${BASE_URL}/account" && echo
      ;;
  esac
  sleep 0.5
done
