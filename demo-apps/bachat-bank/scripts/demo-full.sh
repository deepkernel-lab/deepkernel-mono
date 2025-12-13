#!/usr/bin/env bash
#
# DeepKernel Full Demo Script
#
# This script demonstrates the complete DeepKernel threat detection flow:
# 1. Train baseline model on normal behavior
# 2. Inject malicious behavior (excessive connect syscalls)
# 3. ML detects anomaly, creates THREAT verdict
# 4. Generate and display Seccomp policy
#
# Prerequisites:
# - DeepKernel server running at DK_SERVER_URL (default: localhost:9090)
# - DeepKernel agent running on the same host
# - bachat-bank running in Docker
#
# Usage: ./scripts/demo-full.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(dirname "$SCRIPT_DIR")"

DK_SERVER_URL="${DK_SERVER_URL:-http://localhost:9090}"
BACKEND_URL="${BACKEND_URL:-http://localhost:8000}"
CONTAINER_ID="${CONTAINER_ID:-bachat-bank_backend_1}"

echo "╔════════════════════════════════════════════════════════════════════╗"
echo "║              DeepKernel Full Demo                                  ║"
echo "╠════════════════════════════════════════════════════════════════════╣"
echo "║  Server:    $DK_SERVER_URL"
echo "║  Backend:   $BACKEND_URL"
echo "║  Container: $CONTAINER_ID"
echo "╚════════════════════════════════════════════════════════════════════╝"
echo ""

# Check server health
echo "Checking DeepKernel server health..."
if ! curl -s "$DK_SERVER_URL/api/health" > /dev/null 2>&1; then
    echo " DeepKernel server not responding at $DK_SERVER_URL"
    exit 1
fi
echo " Server is healthy"
echo ""

# Phase 1: Baseline (normal mode)
echo "═══════════════════════════════════════════════════════════════════"
echo " PHASE 1: Establish Normal Baseline"
echo "═══════════════════════════════════════════════════════════════════"
echo ""
echo "Setting backend to NORMAL mode..."
export MODE=normal
docker-compose -f "$COMPOSE_DIR/docker-compose.yml" up -d --build backend 2>/dev/null || true
sleep 3

echo "Generating normal traffic..."
for i in {1..10}; do
    curl -s "$BACKEND_URL/health" > /dev/null
    curl -s -X POST "$BACKEND_URL/login" \
        -H "Content-Type: application/json" \
        -d '{"username":"demo","password":"demo"}' > /dev/null
    sleep 1
done
echo " Normal baseline established"
echo ""

# Phase 2: Inject Threat
echo "═══════════════════════════════════════════════════════════════════"
echo "📍 PHASE 2: Inject Malicious Behavior"
echo "═══════════════════════════════════════════════════════════════════"
echo ""
echo "  Switching backend to MALICIOUS mode..."
export MODE=malicious
export EXFIL_URL="https://evil-server.com:4444/steal"
docker-compose -f "$COMPOSE_DIR/docker-compose.yml" up -d --build backend 2>/dev/null || true
sleep 3

echo ""
echo "Malicious behavior:"
echo "  → Beacon to $EXFIL_URL every 5s (connect to high port)"
echo "  → Shell exec: cat /etc/passwd on /account requests"
echo ""

echo "Generating malicious traffic..."
TOKEN=$(curl -s -X POST "$BACKEND_URL/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"demo","password":"demo"}' 2>/dev/null | grep -o '"token":"[^"]*"' | cut -d'"' -f4 || echo "demo-token")

for i in {1..5}; do
    echo "  Request $i/5 (triggers connect + exec)..."
    curl -s "$BACKEND_URL/account" -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1 || true
    sleep 2
done
echo ""

# Phase 3: Create Policy (Demo)
echo "═══════════════════════════════════════════════════════════════════"
echo " PHASE 3: Generate Seccomp Policy"
echo "═══════════════════════════════════════════════════════════════════"
echo ""

# Create a policy via the server API
echo "Creating PENDING policy for $CONTAINER_ID..."
POLICY_RESPONSE=$(curl -s -X POST "$DK_SERVER_URL/api/demo/policies" \
    -H "Content-Type: application/json" \
    -d "{
        \"containerId\": \"$CONTAINER_ID\",
        \"type\": \"SECCOMP\",
        \"status\": \"PENDING\"
    }")
echo "Policy created: $POLICY_RESPONSE"
echo ""

# Show where physical policy would be
POLICY_DIR="${DK_POLICY_DIR:-/var/lib/deepkernel/policies}"
echo " Seccomp profile location: $POLICY_DIR/"
echo ""

# Create a sample Seccomp profile for demo
mkdir -p "$POLICY_DIR" 2>/dev/null || true
cat > "$POLICY_DIR/policy-$CONTAINER_ID.json" 2>/dev/null << 'SECCOMP' || true
{
  "defaultAction": "SCMP_ACT_ALLOW",
  "architectures": ["SCMP_ARCH_X86_64", "SCMP_ARCH_X86"],
  "comment": "DeepKernel generated - block connect() for threat containment",
  "syscalls": [
    {
      "names": ["connect"],
      "action": "SCMP_ACT_ERRNO",
      "comment": "Block outbound connections - anomalous connect() pattern detected"
    }
  ]
}
SECCOMP

if [ -f "$POLICY_DIR/policy-$CONTAINER_ID.json" ]; then
    echo " Seccomp profile written to $POLICY_DIR/policy-$CONTAINER_ID.json"
    echo ""
    echo "Profile contents:"
    cat "$POLICY_DIR/policy-$CONTAINER_ID.json"
    echo ""
fi

# Update policy to APPLIED
echo "Updating policy status to APPLIED..."
curl -s -X PUT "$DK_SERVER_URL/api/demo/policies/policy-demo/status" \
    -H "Content-Type: application/json" \
    -d '{"status": "APPLIED"}' > /dev/null 2>&1 || true

# Summary
echo ""
echo "╔════════════════════════════════════════════════════════════════════╗"
echo "║                    DEMO COMPLETE                                   ║"
echo "╠════════════════════════════════════════════════════════════════════╣"
echo "║  Normal baseline established                                       ║"
echo "║  Malicious connect() calls injected                                ║"
echo "║  Seccomp policy generated                                          ║"
echo "║                                                                    ║"
echo "║  View results:                                                     ║"
echo "║  • Dashboard:    $DK_SERVER_URL"
echo "║  • Live Events:  $DK_SERVER_URL (click Live Events tab)"
echo "║  • Policies API: $DK_SERVER_URL/api/ui/policies"
echo "║  • Backend logs: docker-compose logs -f backend"
echo "╚════════════════════════════════════════════════════════════════════╝"
echo ""

# Optional: Show backend logs
read -p "Show backend logs? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    docker-compose -f "$COMPOSE_DIR/docker-compose.yml" logs --tail=30 backend
fi

