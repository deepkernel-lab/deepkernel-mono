#!/usr/bin/env bash
#
# DeepKernel Demo: BLAST Anomaly Injection
#
# This creates a PURE anomaly window by flooding with syscalls
# the model has NEVER seen, in rapid succession.
#
# The goal is to dominate the entire 5-second scoring window
# with foreign syscalls so the score drops to < -0.85
#
set -euo pipefail

CONTAINER="${1:-bachat-bank_backend_1}"
DURATION="${2:-10}"

echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║  BLAST MODE: Pure Anomaly Injection                          ║"
echo "║  Container: $CONTAINER"
echo "║  Duration: ${DURATION}s"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo ""

# Check container exists
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "ERROR: Container $CONTAINER not running!"
    exit 1
fi

echo "Injecting FOREIGN syscalls (execve, fork, connect, openat)..."
echo "These syscalls are 0% in training data!"
echo ""

# Method 1: Rapid shell exec flood (execve + fork + clone + wait)
echo "[1/4] Flooding execve/fork/clone..."
docker exec "$CONTAINER" sh -c '
for i in $(seq 1 500); do
    /bin/true
    /bin/echo x > /dev/null
    /bin/cat /dev/null
done
' &

# Method 2: File reconnaissance (openat on sensitive paths)  
echo "[2/4] Flooding openat on /etc/passwd, /proc/*..."
docker exec "$CONTAINER" sh -c '
for i in $(seq 1 200); do
    cat /etc/passwd > /dev/null 2>&1
    cat /etc/hosts > /dev/null 2>&1
    cat /proc/self/maps > /dev/null 2>&1
    cat /proc/cpuinfo > /dev/null 2>&1
    ls -la /etc/ > /dev/null 2>&1
done
' &

# Method 3: Network scanning pattern (connect to many ports)
echo "[3/4] Flooding connect() to random ports..."
docker exec "$CONTAINER" python3 -c '
import socket
import random

for _ in range(300):
    for port in random.sample(range(3000, 9000), 20):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.01)
            s.connect(("1.1.1.1", port))
        except:
            pass
        finally:
            try:
                s.close()
            except:
                pass
' 2>/dev/null &

# Method 4: Memory operations (mmap flood)
echo "[4/4] Flooding mmap/mprotect..."
docker exec "$CONTAINER" python3 -c '
import mmap
import os

for _ in range(500):
    try:
        m = mmap.mmap(-1, 65536)
        m.write(b"X" * 1000)
        m.close()
    except:
        pass
' 2>/dev/null &

# Wait for duration
echo ""
echo "Running for ${DURATION} seconds..."
sleep "$DURATION"

# Kill background processes
jobs -p | xargs -r kill 2>/dev/null || true

echo ""
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║  BLAST COMPLETE                                               ║"
echo "║                                                               ║"
echo "║  Syscalls injected:                                          ║"
echo "║  - execve/fork:  ~1500 calls (0% in training)                ║"
echo "║  - openat:       ~1000 calls on /etc/*, /proc/*              ║"
echo "║  - connect:      ~6000 attempts to ports 3000-9000           ║"
echo "║  - mmap:         ~500 allocations                            ║"
echo "║                                                               ║"
echo "║  Expected score: < -0.85 (VERY ANOMALOUS)                    ║"
echo "╚═══════════════════════════════════════════════════════════════╝"

