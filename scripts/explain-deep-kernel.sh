#!/bin/bash
#
# DeepKernel Demo: Container Syscall Filtering Explained
# 
# This script demonstrates how DeepKernel correlates syscalls from the
# kernel to specific containers - filtering millions of system-wide 
# syscalls to just those from the target container.
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Default container
CONTAINER_NAME="${1:-bachat-bank_backend_1}"

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║     DeepKernel: Container Syscall Filtering - Deep Dive          ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ============================================================================
# STEP 0: The Problem
# ============================================================================
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}THE PROBLEM: System-Wide Syscall Storm${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

# Show current syscall rate
echo -e "${CYAN}Current system-wide syscall rate (sampling 1 second):${NC}"
BEFORE=$(cat /proc/stat | grep -E "^cpu " | awk '{print $2+$3+$4+$5+$6+$7+$8}')
sleep 1
AFTER=$(cat /proc/stat | grep -E "^cpu " | awk '{print $2+$3+$4+$5+$6+$7+$8}')

# Count all processes
TOTAL_PROCESSES=$(ps aux | wc -l)
TOTAL_THREADS=$(ls /proc/*/task 2>/dev/null | wc -l)

echo -e "  Active processes:     ${BOLD}$TOTAL_PROCESSES${NC}"
echo -e "  Active threads:       ${BOLD}$TOTAL_THREADS${NC}"
echo ""
echo -e "  ${RED}Every process generates syscalls. Without filtering, we'd be${NC}"
echo -e "  ${RED}drowning in millions of irrelevant events per second!${NC}"
echo ""

read -p "Press Enter to see how we solve this... "
echo ""

# ============================================================================
# STEP 1: Container Identification
# ============================================================================
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}STEP 1: Container Identification${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "${CYAN}Target container: ${BOLD}$CONTAINER_NAME${NC}"
echo ""

# Get container ID from Docker
echo -e "Querying Docker API for container ID..."
echo -e "   ${BLUE}Command: docker inspect $CONTAINER_NAME --format '{{.Id}}'${NC}"
echo ""

CONTAINER_ID=$(docker inspect "$CONTAINER_NAME" --format '{{.Id}}' 2>/dev/null || echo "")

if [ -z "$CONTAINER_ID" ]; then
    echo -e "${RED}Container '$CONTAINER_NAME' not found!${NC}"
    echo ""
    echo "Available containers:"
    docker ps --format "  - {{.Names}}"
    exit 1
fi

SHORT_ID="${CONTAINER_ID:0:12}"
echo -e "   ${GREEN}✓ Full Container ID:  ${BOLD}$CONTAINER_ID${NC}"
echo -e "   ${GREEN}✓ Short ID:           ${BOLD}$SHORT_ID${NC}"
echo ""

read -p "Press Enter ... "
echo ""

# ============================================================================
# STEP 2: cgroup Mapping
# ============================================================================
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}STEP 2: Linux cgroup Mapping${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "${CYAN}What is a cgroup?${NC}"
echo -e "  Control Groups (cgroups) are a Linux kernel feature that isolates"
echo -e "  and tracks resource usage for groups of processes - this is how"
echo -e "  Docker containers are implemented at the kernel level!"
echo ""

# Get container's main PID
CONTAINER_PID=$(docker inspect "$CONTAINER_NAME" --format '{{.State.Pid}}' 2>/dev/null)
echo -e "Container's main PID: ${BOLD}$CONTAINER_PID${NC}"
echo ""

echo -e "Reading cgroup info from /proc/$CONTAINER_PID/cgroup:"
echo -e "   ${BLUE}Command: cat /proc/$CONTAINER_PID/cgroup${NC}"
echo ""

if [ -f "/proc/$CONTAINER_PID/cgroup" ]; then
    echo -e "${CYAN}   ┌─────────────────────────────────────────────────────────────┐${NC}"
    cat "/proc/$CONTAINER_PID/cgroup" | while read line; do
        echo -e "${CYAN}   │${NC} $line"
    done
    echo -e "${CYAN}   └─────────────────────────────────────────────────────────────┘${NC}"
else
    echo -e "   ${RED}Cannot read cgroup (need root privileges)${NC}"
fi
echo ""

# Extract the cgroup path
CGROUP_PATH=$(cat "/proc/$CONTAINER_PID/cgroup" 2>/dev/null | grep -E "docker|containerd" | head -1 | cut -d: -f3)
echo -e "Extracted cgroup path: ${BOLD}$CGROUP_PATH${NC}"
echo ""

read -p "Press any key ... "
echo ""

# ============================================================================
# STEP 3: cgroup ID (Kernel Identifier)
# ============================================================================
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}STEP 3: cgroup ID - The Kernel's Unique Identifier${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "${CYAN}The cgroup ID is the inode number of the cgroup directory.${NC}"
echo -e "${CYAN}This is the SAME ID that eBPF sees for every syscall!${NC}"
echo ""

# Try to find the cgroup filesystem path
CGROUP_FS_PATH=""
if [ -d "/sys/fs/cgroup/system.slice" ]; then
    # cgroup v2 unified hierarchy
    CGROUP_FS_PATH="/sys/fs/cgroup/system.slice/docker-${CONTAINER_ID}.scope"
elif [ -d "/sys/fs/cgroup/memory/docker" ]; then
    # cgroup v1
    CGROUP_FS_PATH="/sys/fs/cgroup/memory/docker/$CONTAINER_ID"
elif [ -d "/sys/fs/cgroup/docker" ]; then
    CGROUP_FS_PATH="/sys/fs/cgroup/docker/$CONTAINER_ID"
fi

if [ -d "$CGROUP_FS_PATH" ]; then
    CGROUP_INODE=$(stat -c %i "$CGROUP_FS_PATH" 2>/dev/null || echo "N/A")
    echo -e "cgroup filesystem path:"
    echo -e "   ${BOLD}$CGROUP_FS_PATH${NC}"
    echo ""
    echo -e "cgroup ID (inode): ${BOLD}${GREEN}$CGROUP_INODE${NC}"
else
    # Fallback: get from /proc
    CGROUP_INODE=$(cat /proc/$CONTAINER_PID/cgroup 2>/dev/null | head -1 | cut -d: -f3 | xargs -I{} stat -c %i /sys/fs/cgroup{} 2>/dev/null || echo "N/A")
    echo -e "cgroup ID: ${BOLD}${GREEN}$CGROUP_INODE${NC} (estimated)"
fi
echo ""

echo -e "${CYAN}┌─────────────────────────────────────────────────────────────────┐${NC}"
echo -e "${CYAN}│${NC} ${BOLD}KEY INSIGHT:${NC}                                                   ${CYAN}│${NC}"
echo -e "${CYAN}│${NC}                                                                 ${CYAN}│${NC}"
echo -e "${CYAN}│${NC}  Every syscall in Linux carries the cgroup ID of the process   ${CYAN}│${NC}"
echo -e "${CYAN}│${NC}  that made it. This is set by the kernel, NOT the process!     ${CYAN}│${NC}"
echo -e "${CYAN}│${NC}                                                                 ${CYAN}│${NC}"
echo -e "${CYAN}│${NC}  Container: $CONTAINER_NAME                      ${CYAN}│${NC}"
echo -e "${CYAN}│${NC}  cgroup ID: ${GREEN}$CGROUP_INODE${NC}                                           ${CYAN}│${NC}"
echo -e "${CYAN}└─────────────────────────────────────────────────────────────────┘${NC}"
echo ""

read -p "Press any key ... "
echo ""

# ============================================================================
# STEP 4: eBPF Filtering
# ============================================================================
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}STEP 4: eBPF - Kernel-Level Filtering${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "${CYAN}eBPF (extended Berkeley Packet Filter) is a revolutionary Linux${NC}"
echo -e "${CYAN}technology that lets us run sandboxed programs INSIDE the kernel!${NC}"
echo ""

echo -e "${BOLD}DeepKernel eBPF Program Flow:${NC}"
echo ""
echo -e "   ┌───────────────────────────────────────────────────────────────┐"
echo -e "   │                        KERNEL SPACE                          │"
echo -e "   │  ┌─────────────────────────────────────────────────────────┐ │"
echo -e "   │  │              TRACEPOINT: syscalls/sys_enter             │ │"
echo -e "   │  │                                                         │ │"
echo -e "   │  │   for EVERY syscall on the system:                      │ │"
echo -e "   │  │                                                         │ │"
echo -e "   │  │   1. Get cgroup_id = bpf_get_current_cgroup_id()        │ │"
echo -e "   │  │                                                         │ │"
echo -e "   │  │   2. IF cgroup_id NOT in our filter map:                │ │"
echo -e "   │  │        └─> DISCARD (return immediately)                 │ │"
echo -e "   │  │                                                         │ │"
echo -e "   │  │   3. IF cgroup_id IS in our filter map:                 │ │"
echo -e "   │  │        └─> CAPTURE syscall details:                     │ │"
echo -e "   │  │            - syscall ID (read, write, connect, etc.)    │ │"
echo -e "   │  │            - timestamp (nanoseconds)                    │ │"
echo -e "   │  │            - argument classification                    │ │"
echo -e "   │  │            - PID/TID                                    │ │"
echo -e "   │  │                                                         │ │"
echo -e "   │  │   4. Push to ring buffer → userspace agent              │ │"
echo -e "   │  └─────────────────────────────────────────────────────────┘ │"
echo -e "   └───────────────────────────────────────────────────────────────┘"
echo -e "                              │"
echo -e "                              ▼"
echo -e "   ┌───────────────────────────────────────────────────────────────┐"
echo -e "   │                        USER SPACE                            │"
echo -e "   │  ┌─────────────────────────────────────────────────────────┐ │"
echo -e "   │  │              DeepKernel Agent (C++)                     │ │"
echo -e "   │  │                                                         │ │"
echo -e "   │  │   - Receives ONLY filtered syscalls                     │ │"
echo -e "   │  │   - Aggregates into 5-second windows                    │ │"
echo -e "   │  │   - Computes 594-dimension feature vectors              │ │"
echo -e "   │  │   - Sends to ML service for anomaly detection           │ │"
echo -e "   │  └─────────────────────────────────────────────────────────┘ │"
echo -e "   └───────────────────────────────────────────────────────────────┘"
echo ""

read -p "Press Enter ... "
echo ""

# ============================================================================
# STEP 6: Syscall Classification
# ============================================================================
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}STEP 6: Syscall Classification (In-Kernel)${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "${CYAN}The eBPF program classifies each syscall into categories:${NC}"
echo ""

echo -e "┌──────────────┬───────────────────────────────────────────────────────┐"
echo -e "│ ${BOLD}Category${NC}     │ ${BOLD}Example Syscalls${NC}                                      │"
echo -e "├──────────────┼───────────────────────────────────────────────────────┤"
echo -e "│ ${GREEN}FILE${NC}         │ read, write, open, close, stat, fstat, lseek,         │"
echo -e "│              │ ioctl, epoll_wait, epoll_ctl, mmap                    │"
echo -e "├──────────────┼───────────────────────────────────────────────────────┤"
echo -e "│ ${BLUE}NET${NC}          │ socket, connect, accept, sendto, recvfrom, bind,      │"
echo -e "│              │ listen, getsockname, getpeername, setsockopt          │"
echo -e "├──────────────┼───────────────────────────────────────────────────────┤"
echo -e "│ ${YELLOW}PROC${NC}         │ fork, clone, execve, exit, wait4, kill, getpid,      │"
echo -e "│              │ getuid, geteuid, setuid                               │"
echo -e "├──────────────┼───────────────────────────────────────────────────────┤"
echo -e "│ ${CYAN}MEM${NC}          │ brk, mmap, munmap, mprotect, mremap                   │"
echo -e "├──────────────┼───────────────────────────────────────────────────────┤"
echo -e "│ ${RED}OTHER${NC}        │ Everything else (rare/uncategorized)                  │"
echo -e "└──────────────┴───────────────────────────────────────────────────────┘"
echo ""

echo -e "${CYAN}This classification is used to build the 594-dimension feature${NC}"
echo -e "${CYAN}vector for the Isolation Forest ML model!${NC}"
echo ""

read -p "Press Enter ... "
echo ""

# ============================================================================
# SUMMARY
# ============================================================================
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}SUMMARY: The Complete Filtering Pipeline${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""

echo -e "  ┌─────────────────┐"
echo -e "  │  Container:     │"
echo -e "  │  ${BOLD}$CONTAINER_NAME${NC}"
echo -e "  └────────┬────────┘"
echo -e "           │"
echo -e "           ▼"
echo -e "  ┌─────────────────┐     Docker API"
echo -e "  │  Container ID   │◄────────────────"
echo -e "  │  ${BOLD}${SHORT_ID}${NC}  │"
echo -e "  └────────┬────────┘"
echo -e "           │"
echo -e "           ▼"
echo -e "  ┌─────────────────┐     /proc/<pid>/cgroup"
echo -e "  │  cgroup Path    │◄────────────────"
echo -e "  │  docker/${SHORT_ID} │"
echo -e "  └────────┬────────┘"
echo -e "           │"
echo -e "           ▼"
echo -e "  ┌─────────────────┐     stat (inode)"
echo -e "  │  ${GREEN}cgroup ID${NC}       │◄────────────────"
echo -e "  │  ${BOLD}${GREEN}$CGROUP_INODE${NC}            │"
echo -e "  └────────┬────────┘"
echo -e "           │"
echo -e "           ▼"
echo -e "  ┌─────────────────────────────────────┐"
echo -e "  │  ${BOLD}eBPF (kernel-level filtering)${NC}       │"
echo -e "  │                                     │"
echo -e "  │  IF cgroup_id == $CGROUP_INODE:       │"
echo -e "  │     CAPTURE syscall                 │"
echo -e "  │  ELSE:                              │"
echo -e "  │     DISCARD (99.9% of syscalls)     │"
echo -e "  └────────┬────────────────────────────┘"
echo -e "           │"
echo -e "           ▼"
echo -e "  ┌─────────────────────────────────────┐"
echo -e "  │  ${BOLD}DeepKernel Agent → ML Service${NC}       │"
echo -e "  │                                     │"
echo -e "  │  5-sec windows → 594-dim features   │"
echo -e "  │  → Isolation Forest → Anomaly Score │"
echo -e "  └─────────────────────────────────────┘"
echo ""

echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}${BOLD}✓ This is where the DeepKernel eBPF agent takes over!${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "To see the agent in action:"
echo -e "  ${CYAN}sudo DK_CONTAINER_FILTER=\"$CONTAINER_NAME\" ./deepkernel-agent${NC}"
echo ""


