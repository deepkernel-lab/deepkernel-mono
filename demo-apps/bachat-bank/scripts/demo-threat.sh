#!/usr/bin/env bash
#
# DeepKernel Demo: Trigger THREAT Scenario
#
# This script:
# 1. Switches bachat-bank backend to malicious mode
# 2. Generates traffic to trigger connect() syscalls
# 3. The ML model should detect anomalous behavior
# 4. DeepKernel generates Seccomp policy
#
# Usage: ./scripts/demo-threat.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(dirname "$SCRIPT_DIR")"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║          DeepKernel Demo: THREAT Scenario                     ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Step 1: Switch to malicious mode
echo " Step 1: Switching backend to MALICIOUS mode..."
export MODE=malicious
export EXFIL_URL="https://evil-server.com:4444/steal"  # High port = suspicious
docker-compose -f "$COMPOSE_DIR/docker-compose.yml" up -d --build backend
sleep 3

echo ""
echo " Step 2: Generating traffic to trigger anomalous connect() calls..."
echo "   The malicious backend will:"
echo "   - Beacon to $EXFIL_URL every 5 seconds"
echo "   - Run 'cat /etc/passwd' on /account requests"
echo ""

# Step 2: Generate traffic (this triggers the malicious behavior)
BACKEND_URL="${BACKEND_URL:-http://localhost:8000}"

echo "   Calling /health..."
curl -s "$BACKEND_URL/health" | head -c 100
echo ""

echo "   Logging in..."
TOKEN=$(curl -s -X POST "$BACKEND_URL/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "   Token: ${TOKEN:0:20}..."

echo ""
echo "   Calling /account (triggers malicious exec + connect)..."
for i in {1..5}; do
  echo "   Request $i/5..."
  curl -s "$BACKEND_URL/account" -H "Authorization: Bearer $TOKEN" > /dev/null
  sleep 2
done

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║   Malicious traffic generated!                               ║"
echo "║                                                                ║"
echo "║  What happens next:                                           ║"
echo "║  1. Agent captures syscall windows                            ║"
echo "║  2. Server scores with ML model → HIGH anomaly score          ║"
echo "║  3. Triage determines THREAT verdict                          ║"
echo "║  4. Seccomp policy generated at /var/lib/deepkernel/policies  ║"
echo "║                                                                ║"
echo "║  Check the UI at http://<server>:9090 for:                    ║"
echo "║  - Live Events showing THREAT                                 ║"
echo "║  - Dashboard showing anomaly scores                           ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Show recent backend logs
echo " Backend logs (showing malicious activity):"
docker-compose -f "$COMPOSE_DIR/docker-compose.yml" logs --tail=20 backend

