# DeepKernel Agent

The DeepKernel Agent is an eBPF-based syscall monitoring agent that captures per-container system call traces and sends them to the DeepKernel server for anomaly detection and triage.

## Features

- **eBPF-based syscall capture** - Low overhead monitoring using kernel tracepoints
- **Docker container mapping** - Automatically resolves Docker container names from cgroup IDs
- **Syscall classification** - Categorizes syscalls into FILE, NET, PROC, MEM, OTHER classes
- **5-second window streaming** - Sends short windows to server for real-time analysis
- **Long dump support** - Records extended traces for baseline training
- **Policy enforcement** - Receives and applies Seccomp policies from server
- **Container filtering** - Monitor only specific containers via regex patterns

## Prerequisites

### System Requirements
- Linux kernel 5.8+ (for ring buffer support)
- Root/CAP_BPF privileges for eBPF operations
- Docker installed (for container monitoring)

### Build Dependencies

```bash
# Ubuntu/Debian
sudo apt-get install -y \
    build-essential \
    cmake \
    clang \
    llvm \
    libbpf-dev \
    libcurl4-openssl-dev \
    linux-headers-$(uname -r)

# Fedora/RHEL
sudo dnf install -y \
    gcc-c++ \
    cmake \
    clang \
    llvm \
    libbpf-devel \
    libcurl-devel \
    kernel-devel
```

## Building

```bash
cd agent
mkdir build && cd build
cmake ..
make -j$(nproc)
```

This produces:
- `deepkernel-agent` - The main agent binary
- `deepkernel.bpf.o` - The eBPF program object file

## Running the Agent

### Basic Usage

```bash
# Must run as root for eBPF access
sudo ./deepkernel-agent
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DK_AGENT_ID` | `node-1` | Unique identifier for this agent |
| `DK_SERVER_URL` | `http://localhost:8080` | DeepKernel server URL |
| `DK_NODE_NAME` | `worker-01` | Kubernetes node name |
| `DK_SHORT_WINDOW_SEC` | `5` | Short window duration (seconds) |
| `DK_MIN_EVENTS_PER_WINDOW` | `20` | Minimum events to trigger window send |
| `DK_LONG_DUMP_DURATION_SEC` | `1200` | Default long dump duration (20 min) |
| `DK_DUMP_DIR` | `/var/lib/deepkernel/dumps` | Directory for dump files |
| `DK_SYSCALL_VOCAB_SIZE` | `256` | Syscall vocabulary size |
| `DK_AUTO_BASELINE_DUMP` | `0` | Auto-start baseline dump (0=no, 1=yes) |
| `DK_DOCKER_SOCKET` | `/var/run/docker.sock` | Docker socket path |
| `DK_CONTAINER_CACHE_TTL` | `60` | Container name cache TTL (seconds) |
| `DK_AGENT_LISTEN_PORT` | `8081` | HTTP server port for commands |
| `DK_CONTAINER_FILTER` | `` | Regex to filter containers (empty=all) |
| `DK_POLICY_DIR` | `/var/lib/deepkernel/policies` | Directory for policy files |
| `DK_POLICY_ENFORCEMENT_MODE` | `ERRNO` | Policy action: `ERRNO` (block) or `LOG` (audit only) |

### Example: Demo Configuration

```bash
export DK_AGENT_ID="demo-agent"
export DK_SERVER_URL="http://localhost:8080"
export DK_CONTAINER_FILTER="bachat"
export DK_DUMP_DIR="./dumps"
export DK_POLICY_DIR="./policies"
export DK_POLICY_ENFORCEMENT_MODE="ERRNO"  # or "LOG" for dry-run

sudo -E ./deepkernel-agent
```

### Enforcement Modes

**ERRNO Mode (Default)** - Active blocking
- Syscalls are denied with `EPERM` error
- Container sees "Operation not permitted"
- Demonstrates actual threat mitigation
- **Risk:** May break legitimate traffic if policy is too broad

**LOG Mode** - Audit only
- Syscalls are logged to audit subsystem
- Container behavior unchanged
- Safe for testing without disruption
- Check logs: `sudo ausearch -m SECCOMP`

## Docker Permissions

To monitor Docker containers, the agent needs access to:

1. **Docker socket** - For container name resolution
   ```bash
   # Ensure docker.sock is accessible
   sudo chmod 666 /var/run/docker.sock
   # OR add user to docker group
   sudo usermod -aG docker $USER
   ```

2. **eBPF capabilities** - For attaching BPF programs
   ```bash
   # Run as root, or with capabilities:
   sudo setcap cap_bpf,cap_perfmon,cap_sys_admin+ep ./deepkernel-agent
   ```

## API Endpoints (Agent HTTP Server)

The agent exposes an HTTP server for receiving commands from the DeepKernel server:

### Health Check
```
GET /health
Response: {"status":"healthy"}
```

### Request Long Dump
```
POST /long-dump-requests
Content-Type: application/json

{
  "container_id": "demo-backend",
  "duration_sec": 1200,
  "reason": "INITIAL_TRAINING"
}

Response: 202 Accepted
{"status":"dump_requested"}
```

### Apply Policy
```
POST /policies
Content-Type: application/json

{
  "container_id": "demo-backend",
  "policy_id": "policy-001",
  "type": "SECCOMP",
  "spec": {
    "syscalls": [
      {"name": "connect", "action": "SCMP_ACT_ERRNO"}
    ]
  }
}

Response: 200 OK
{"status":"APPLIED","policy_id":"policy-001"}
```

## Syscall Classification

The agent classifies syscalls into these categories:

| Class | Value | Syscalls |
|-------|-------|----------|
| FILE | 1 | read, write, open, close, stat, fstat, openat, etc. |
| NET | 2 | socket, connect, accept, sendto, recvfrom, etc. |
| PROC | 3 | clone, fork, vfork, execve, exit, wait4, etc. |
| MEM | 4 | mmap, mprotect, munmap, brk, etc. |
| OTHER | 0 | All other syscalls |

## Troubleshooting

### eBPF Loading Fails

```
Failed to load BPF object
```

**Solutions:**
1. Ensure kernel version is 5.8+: `uname -r`
2. Check BPF is enabled: `cat /proc/config.gz | gunzip | grep BPF`
3. Run as root or with proper capabilities

### Container Names Not Resolving

```
Container ID shows as "host-12345" instead of name
```

**Solutions:**
1. Check Docker socket access: `ls -la /var/run/docker.sock`
2. Verify containers are running: `docker ps`
3. Check cgroup version: `cat /proc/filesystems | grep cgroup`

### Windows Not Sending

```
Failed to POST window for container
```

**Solutions:**
1. Verify server URL: `curl http://localhost:8080/health`
2. Check network connectivity
3. Review `DK_MIN_EVENTS_PER_WINDOW` setting

## Running Tests

```bash
cd build
make agent-tests
./agent-tests
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      eBPF Kernel Space                       │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ sys_enter tracepoint → classify → ring buffer           ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     Agent User Space                         │
│  ┌──────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │   Event  │→│ Docker Mapper │→│   Buffer Manager     │  │
│  │   Loop   │  │ (cgroup→name) │  │ (windows, dumps)     │  │
│  └──────────┘  └──────────────┘  └──────────────────────┘  │
│       │                                     │               │
│       ▼                                     ▼               │
│  ┌──────────┐                         ┌──────────────┐     │
│  │  HTTP    │←───────────────────────→│ HTTP Client  │     │
│  │  Server  │ (commands from server)  │ (to server)  │     │
│  └──────────┘                         └──────────────┘     │
│       │                                                     │
│       ▼                                                     │
│  ┌────────────────┐                                        │
│  │ Policy Enforcer│ → Docker API (Seccomp profiles)        │
│  └────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
```

## Integration with Demo

For the Bachat Bank demo:

1. Start the DeepKernel server
2. Start the agent with container filter:
   ```bash
   export DK_CONTAINER_FILTER="bachat"
   sudo -E ./deepkernel-agent
   ```
3. Start the demo app: `docker-compose up -d`
4. Agent will automatically:
   - Detect containers matching "bachat" pattern
   - Send 5-second windows to server
   - Accept policy enforcement commands

## Policy Enforcement Limitations & Workarounds

### Seccomp Port Filtering Challenge

**Problem:** Seccomp cannot directly filter by destination port because:
- Seccomp inspects syscall arguments (register values)
- Port is inside `sockaddr*` structure (requires memory dereference)
- Seccomp lacks memory access capabilities

**Current Behavior:**
When the server sends a policy to "deny high ports", the agent generates a Seccomp profile that blocks **ALL** `connect` syscalls (not just high ports).

**Workarounds for Demo:**

1. **Use LOG mode** - Profile is applied but only audits, doesn't block:
   ```bash
   export DK_POLICY_ENFORCEMENT_MODE="LOG"
   ```
   Then demonstrate by showing audit logs: `sudo ausearch -m SECCOMP`

2. **Scope threats carefully** - Have the server send specific syscall names:
   - For file threats: `openat`, `unlink` (more targetable)
   - For process threats: `execve`, `ptrace`
   - For network: Accept that `connect` blocks all, or use LOG mode

3. **Use alternative enforcement** (production approach):
   - **eBPF LSM hooks** - Can inspect full sockaddr structure
   - **Network policies** - Use iptables/nftables for IP/port filtering
   - **Container restart** with allow-list - Restart with limited connect permissions

### Demo Recommendation

**For THREAT scenario (malicious beacon to external high port):**
```bash
# Option A: Block mode (demonstrates enforcement)
export DK_POLICY_ENFORCEMENT_MODE="ERRNO"
# Shows: Container gets EPERM on connect, attack blocked
# Risk: May also block legitimate connections

# Option B: Audit mode (safer)
export DK_POLICY_ENFORCEMENT_MODE="LOG"  
# Shows: Policy generated, audit logs capture attempts
# Benefit: No disruption, still demonstrates detection
```

**For production:** Use eBPF LSM or network policies for port-specific filtering.

## License

See the main repository LICENSE file.

