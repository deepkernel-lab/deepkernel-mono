# DeepKernel Agent

The DeepKernel Agent is an eBPF-based syscall monitoring agent that captures per-container system call traces and sends them to the DeepKernel server for anomaly detection and triage.

## Features

- **eBPF-based syscall capture** - Low overhead monitoring using kernel tracepoints
- **Multi-runtime container support** - Docker, containerd, CRI-O with automatic detection
- **Kubernetes-native** - Pod name resolution, namespace awareness, downward API support
- **Syscall classification** - Categorizes syscalls into FILE, NET, PROC, MEM, OTHER classes
- **5-second window streaming** - Sends short windows to server for real-time analysis
- **Long dump support** - Records extended traces for baseline training
- **Policy enforcement** - Receives and applies Seccomp policies from server
- **Container filtering** - Monitor only specific containers via regex patterns

## Prerequisites

### System Requirements
- Linux kernel 5.8+ (for ring buffer support)
- Root/CAP_BPF privileges for eBPF operations
- Container runtime: Docker, containerd, or CRI-O
- For Kubernetes: `crictl` CLI tool (for containerd/CRI-O name resolution)

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

#### Basic Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DK_AGENT_ID` | `node-1` | Unique identifier for this agent |
| `DK_SERVER_URL` | `http://localhost:9090` | DeepKernel server URL |
| `DK_NODE_NAME` | `worker-01` | Kubernetes node name |
| `DK_SHORT_WINDOW_SEC` | `5` | Short window duration (seconds) |
| `DK_MIN_EVENTS_PER_WINDOW` | `20` | Minimum events to trigger window send |
| `DK_LONG_DUMP_DURATION_SEC` | `1200` | Default long dump duration (20 min) |
| `DK_DUMP_DIR` | `/var/lib/deepkernel/dumps` | Directory for dump files |
| `DK_SYSCALL_VOCAB_SIZE` | `256` | Syscall vocabulary size |
| `DK_AUTO_BASELINE_DUMP` | `0` | Auto-start baseline dump (0=no, 1=yes) |
| `DK_AGENT_LISTEN_PORT` | `8082` | HTTP server port for commands |
| `DK_CONTAINER_FILTER` | `` | Regex to filter containers (empty=all) |

#### Container Runtime Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DK_USE_LEGACY_MAPPER` | `true` | Use fast legacy DockerMapper (recommended for Docker) |
| `DK_CONTAINER_RUNTIME` | `docker` | Runtime: `docker`, `containerd`, `crio`, `auto` |
| `DK_DOCKER_SOCKET` | `/var/run/docker.sock` | Docker socket path |
| `DK_CONTAINERD_SOCKET` | `/run/containerd/containerd.sock` | Containerd socket path |
| `DK_CRIO_SOCKET` | `/var/run/crio/crio.sock` | CRI-O socket path |
| `DK_CRICTL_PATH` | `/usr/bin/crictl` | Path to crictl binary |
| `DK_CONTAINER_CACHE_TTL` | `60` | Container name cache TTL (seconds) |

#### Kubernetes Settings (only when DK_USE_LEGACY_MAPPER=false)

| Variable | Default | Description |
|----------|---------|-------------|
| `DK_ENABLE_K8S_API` | `false` | Query Kubernetes API for pod metadata |
| `DK_PREFER_POD_NAME` | `false` | Use pod name instead of container name |

#### Policy Settings

| Variable | Default | Description |
|----------|---------|-------------|
| `DK_POLICY_DIR` | `/var/lib/deepkernel/policies` | Directory for policy files |
| `DK_POLICY_ENFORCEMENT_MODE` | `ERRNO` | Policy action: `ERRNO` (block) or `LOG` (audit only) |

### Example: Demo Configuration

```bash
export DK_AGENT_ID="demo-agent"
export DK_SERVER_URL="http://localhost:9090"
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

## Container Runtime Support

The agent supports multiple container runtimes with automatic detection:

### Docker (Default)

```bash
# Ensure docker.sock is accessible
sudo chmod 666 /var/run/docker.sock
# OR add user to docker group
sudo usermod -aG docker $USER

# Run with Docker
sudo ./deepkernel-agent
```

### Kubernetes with containerd

For Kubernetes clusters using containerd (no Docker):

```bash
# Install crictl (required for containerd name resolution)
VERSION="v1.28.0"
curl -L "https://github.com/kubernetes-sigs/cri-tools/releases/download/${VERSION}/crictl-${VERSION}-linux-amd64.tar.gz" | \
  sudo tar -C /usr/local/bin -xz

# Configure crictl for containerd
sudo crictl config --set runtime-endpoint=unix:///run/containerd/containerd.sock

# Run agent with containerd
export DK_CONTAINER_RUNTIME=containerd
sudo -E ./deepkernel-agent
```

**Kubernetes DaemonSet deployment:**

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: deepkernel-agent
  namespace: deepkernel
spec:
  selector:
    matchLabels:
      app: deepkernel-agent
  template:
    metadata:
      labels:
        app: deepkernel-agent
    spec:
      hostPID: true
      hostNetwork: true
      containers:
      - name: agent
        image: deepkernel/agent:latest
        securityContext:
          privileged: true
        env:
        - name: DK_CONTAINER_RUNTIME
          value: "containerd"
        - name: DK_ENABLE_K8S_API
          value: "true"
        - name: DK_PREFER_POD_NAME
          value: "true"
        - name: KUBERNETES_POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: KUBERNETES_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        volumeMounts:
        - name: containerd-socket
          mountPath: /run/containerd/containerd.sock
        - name: sys
          mountPath: /sys
          readOnly: true
        - name: proc
          mountPath: /host/proc
          readOnly: true
      volumes:
      - name: containerd-socket
        hostPath:
          path: /run/containerd/containerd.sock
      - name: sys
        hostPath:
          path: /sys
      - name: proc
        hostPath:
          path: /proc
```

### CRI-O (OpenShift/Kubernetes)

```bash
# Run agent with CRI-O
export DK_CONTAINER_RUNTIME=crio
export DK_CRIO_SOCKET=/var/run/crio/crio.sock
sudo -E ./deepkernel-agent
```

### Runtime Auto-Detection

By default (`DK_CONTAINER_RUNTIME=auto`), the agent auto-detects the runtime:

1. Check `/var/run/docker.sock` → Docker
2. Check `/run/containerd/containerd.sock` → containerd
3. Check `/var/run/crio/crio.sock` → CRI-O
4. Fall back to crictl if available

### Container Naming

In Kubernetes environments, container names follow this format:

| Setting | Example Container Name |
|---------|----------------------|
| `DK_PREFER_POD_NAME=true` (default) | `prod/payment-api` or `payment-api` |
| `DK_PREFER_POD_NAME=false` | `k8s_payment-api_pod-xyz_prod_...` |

## eBPF Capabilities

For eBPF operations, run as root or with capabilities:

```bash
# Option 1: Run as root
sudo ./deepkernel-agent

# Option 2: Set capabilities
sudo setcap cap_bpf,cap_perfmon,cap_sys_admin+ep ./deepkernel-agent
./deepkernel-agent
```

## API Reference

The agent exposes an HTTP server on port **8082** (configurable via `DK_AGENT_LISTEN_PORT`) for receiving commands from the DeepKernel server.

### Health Check
```http
GET /health
```

Returns agent health status.

**Response:**
```json
{
  "status": "healthy",
  "agent_id": "node-1",
  "uptime_seconds": 3600,
  "containers_monitored": 5
}
```

**Status:** `200 OK`

---

### Request Long Dump
```http
POST /long-dump-requests
Content-Type: application/json
```

Instructs the agent to start a long dump (baseline recording) for a specific container.

**Request:**
```json
{
  "container_id": "bachat-backend",
  "duration_sec": 1200,
  "reason": "INITIAL_TRAINING"
}
```

**Parameters:**
- `container_id` - Container to record (must match filter regex if configured)
- `duration_sec` - Recording duration in seconds
- `reason` - Purpose of dump: `INITIAL_TRAINING`, `RETRAIN`, `DIAGNOSTIC`

**Response:**
```json
{
  "status": "dump_requested",
  "container_id": "bachat-backend",
  "duration_sec": 1200,
  "dump_path": "/var/lib/deepkernel/dumps/bachat-backend.bin"
}
```

**Status:** `202 Accepted`

**Behavior:**
1. Agent starts recording syscalls to binary file
2. After `duration_sec` seconds, dump stops
3. Agent sends completion notification to server: `POST /api/v1/agent/dump-complete`

---

### Apply Security Policy
```http
POST /policies
Content-Type: application/json
```

Applies a Seccomp security policy to a container (called by server when threat detected).

**Request:**
```json
{
  "container_id": "bachat-backend",
  "policy_id": "policy-001",
  "type": "SECCOMP",
  "spec": {
    "version": "1.0",
    "default_action": "SCMP_ACT_ALLOW",
    "syscalls": [
      {
        "name": "connect",
        "action": "SCMP_ACT_ERRNO"
      },
      {
        "name": "execve",
        "action": "SCMP_ACT_LOG"
      }
    ]
  }
}
```

**Policy Types:**
- `SECCOMP` - Syscall filtering (currently supported)
- `NETWORK` - Network policy (future)
- `FILE` - File access policy (future)

**Actions:**
- `SCMP_ACT_ERRNO` - Block syscall with `EPERM` error (enforcement mode)
- `SCMP_ACT_LOG` - Log syscall attempt (audit mode)
- `SCMP_ACT_ALLOW` - Allow syscall

**Response:**
```json
{
  "status": "APPLIED",
  "policy_id": "policy-001",
  "container_id": "bachat-backend",
  "timestamp": "2025-12-12T10:00:00Z"
}
```

**Status:** `200 OK`

**Enforcement:**
1. Agent generates OCI-compliant Seccomp JSON profile
2. Saves to `$DK_POLICY_DIR/<container_id>.json`
3. Updates container via Docker API: `docker update --security-opt seccomp=<profile>`
4. Container restart may be required for enforcement

---

## Outbound API Calls (Agent → Server)

The agent makes these calls to the DeepKernel server:

### Send Syscall Window
```http
POST http://localhost:9090/api/v1/agent/windows
Content-Type: application/json
```

Sends a 5-second syscall window for real-time analysis.

**Frequency:** Every 5 seconds (configurable via `DK_SHORT_WINDOW_SEC`)

**Request:**
```json
{
  "version": 1,
  "agent_id": "node-1",
  "container_id": "bachat-backend",
  "window_start_ts_ns": 1702396800000000000,
  "records": [
    {
      "delta_ts_us": 1234,
      "syscall_id": 257,
      "arg_class": 1,
      "arg_bucket": 2
    },
    ...
  ]
}
```

**Retry Logic:** 3 retries with exponential backoff (1s, 2s, 4s)

---

### Notify Long Dump Complete
```http
POST http://localhost:9090/api/v1/agent/dump-complete
Content-Type: application/json
```

Notifies server when a long dump finishes.

**Request:**
```json
{
  "agent_id": "node-1",
  "container_id": "bachat-backend",
  "dump_path": "/var/lib/deepkernel/dumps/bachat-backend.bin",
  "start_ts_ns": 1702396800000000000,
  "duration_sec": 1200,
  "record_count": 15000
}
```

**Purpose:** Triggers ML model training pipeline on server.

---

## API Summary Table

### Agent HTTP Server (Inbound)

| Method | Endpoint | Description | Called By |
|--------|----------|-------------|-----------|
| `GET` | `/health` | Health check | Server, monitoring |
| `POST` | `/long-dump-requests` | Start baseline recording | Server |
| `POST` | `/policies` | Apply security policy | Server |

### Agent HTTP Client (Outbound)

| Method | Endpoint | Description | Frequency |
|--------|----------|-------------|-----------|
| `POST` | `/api/v1/agent/windows` | Send syscall window | Every 5s |
| `POST` | `/api/v1/agent/dump-complete` | Long dump finished | On completion |

---

## Data Formats

### Syscall Record (Binary Dump)
```c
struct TraceRecord {
    uint32_t delta_ts_us;   // Microseconds since window start
    uint16_t syscall_id;    // x86_64 syscall number
    uint8_t  arg_class;     // Category: 1=FILE, 2=NET, 3=PROC, 4=MEM, 0=OTHER
    uint8_t  arg_bucket;    // Bucketed argument value
} __attribute__((packed));  // 8 bytes
```

### Binary Dump File Structure
```
┌────────────────────────────────────┐
│ Header (80 bytes)                  │
│ - version: uint32                  │
│ - syscall_vocab_size: uint32       │
│ - container_id: char[64]           │
│ - start_ts_ns: uint64              │
├────────────────────────────────────┤
│ TraceRecord 1 (8 bytes)            │
├────────────────────────────────────┤
│ TraceRecord 2 (8 bytes)            │
├────────────────────────────────────┤
│ ...                                │
├────────────────────────────────────┤
│ TraceRecord N (8 bytes)            │
└────────────────────────────────────┘
```

**Usage:** ML training utility (`ml-service/tools/train_from_dump.py`) parses this format.

---

## Error Codes

| Code | Message | Cause |
|------|---------|-------|
| `400` | Invalid request body | Malformed JSON |
| `404` | Container not found | Container not being monitored |
| `500` | Policy application failed | Docker API error |
| `503` | Agent not ready | eBPF not loaded |

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
1. Verify server URL: `curl http://localhost:9090/health`
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

