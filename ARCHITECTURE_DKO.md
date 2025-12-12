# DeepKernel Platform Architecture

> **DKO** - DeepKernel Operations  
> Version: 0.1.0  
> Last Updated: December 2025

## 1. Overview

DeepKernel is an intelligent runtime security platform that uses eBPF-based syscall monitoring, per-container machine learning models, and LLM-powered triage to detect and respond to anomalous container behavior in real-time.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              DeepKernel Platform                             │
│                                                                             │
│  ┌─────────────┐    ┌─────────────────┐    ┌─────────────────────────────┐ │
│  │   eBPF      │    │   Spring Boot   │    │     React Frontend          │ │
│  │   Agent     │───▶│     Server      │◀───│     (Dashboard)             │ │
│  │   (C++)     │    │     (Java)      │    │                             │ │
│  └─────────────┘    └────────┬────────┘    └─────────────────────────────┘ │
│        │                     │                                              │
│        │            ┌────────┴────────┐                                    │
│        │            │   ML Service    │                                    │
│        │            │   (Python)      │                                    │
│        │            └─────────────────┘                                    │
│        │                                                                    │
│        ▼                                                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Monitored Containers (Docker)                     │   │
│  │   ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐               │   │
│  │   │ App 1   │  │ App 2   │  │ App 3   │  │ App N   │               │   │
│  │   └─────────┘  └─────────┘  └─────────┘  └─────────┘               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Architecture

### 2.1 eBPF Agent (C++)

**Location:** `agent/`

The agent runs on the host and captures syscalls from all containers using eBPF tracepoints.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            eBPF Agent                                     │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                     Kernel Space (eBPF)                          │    │
│  │  ┌────────────────┐  ┌─────────────┐  ┌──────────────────┐     │    │
│  │  │ sys_enter      │  │ classify_   │  │ Ring Buffer      │     │    │
│  │  │ tracepoint     │─▶│ arg_class   │─▶│ (events)         │     │    │
│  │  └────────────────┘  └─────────────┘  └────────┬─────────┘     │    │
│  └────────────────────────────────────────────────┼─────────────────┘    │
│                                                   │                       │
│  ┌────────────────────────────────────────────────┼─────────────────┐    │
│  │                     User Space (C++)           │                  │    │
│  │                                                ▼                  │    │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │    │
│  │  │ Docker      │  │ Container   │  │ Event Handler           │  │    │
│  │  │ Mapper      │  │ Buffers     │◀─│ (ring_buffer callback)  │  │    │
│  │  └──────┬──────┘  └──────┬──────┘  └─────────────────────────┘  │    │
│  │         │                │                                       │    │
│  │         ▼                ▼                                       │    │
│  │  ┌─────────────────────────────────────────────────────────┐    │    │
│  │  │              Serialization Layer                         │    │    │
│  │  │  • JSON (short windows) → POST /api/v1/agent/windows    │    │    │
│  │  │  • Binary (long dumps)  → File + notify server          │    │    │
│  │  └─────────────────────────────────────────────────────────┘    │    │
│  │                                                                  │    │
│  │  ┌──────────────────┐  ┌──────────────────────────────────┐    │    │
│  │  │ HTTP Client      │  │ Agent Server (httplib)           │    │    │
│  │  │ (libcurl)        │  │ Port: 8082                       │    │    │
│  │  │ → to server:9090 │  │ Receives: long-dump, policies    │    │    │
│  │  └──────────────────┘  └──────────────────────────────────┘    │    │
│  │                                                                  │    │
│  │  ┌──────────────────────────────────────────────────────────┐  │    │
│  │  │ Policy Enforcer                                           │  │    │
│  │  │ • Generates Seccomp profiles                              │  │    │
│  │  │ • Applies via Docker API (/var/run/docker.sock)           │  │    │
│  │  └──────────────────────────────────────────────────────────┘  │    │
│  └──────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

**Key Components:**

| Component | File | Purpose |
|-----------|------|---------|
| eBPF Program | `ebpf/deepkernel.bpf.c` | Kernel-level syscall capture |
| Agent Core | `src/agent.cpp` | Event handling, buffering, orchestration |
| Docker Mapper | `src/docker_mapper.cpp` | cgroup ID → container name resolution |
| HTTP Client | `src/http_client.cpp` | Communication with server (with retry) |
| Agent Server | `src/agent_server.cpp` | Receives commands from server |
| Policy Enforcer | `src/policy_enforcer.cpp` | Seccomp profile generation & application |
| Serialization | `src/serialization.cpp` | JSON/binary event encoding |

**Data Structures:**

```cpp
// Kernel Event (captured by eBPF)
struct KernelSyscallEvent {
    uint64_t timestamp_ns;
    uint32_t pid;
    uint64_t cgroup_id;
    int syscall_id;
    uint8_t arg_class;   // FILE=0, NET=1, PROC=2, MEM=3, OTHER=4
    uint8_t arg_bucket;  // Bucketed argument value
};

// Trace Record (sent to server)
struct TraceRecord {
    uint32_t delta_ts_us;  // Microseconds since window start
    uint16_t syscall_id;
    uint8_t arg_class;
    uint8_t arg_bucket;
};
```

---

### 2.2 Spring Boot Server (Java)

**Location:** `server/`

The server is the central orchestration hub, implementing a Ports & Adapters (Hexagonal) architecture.

```
┌────────────────────────────────────────────────────────────────────────────┐
│                          Spring Boot Server                                 │
│                                                                            │
│  ┌────────────────────────────────────────────────────────────────────┐   │
│  │                         REST API Layer                              │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌─────────────────┐  │   │
│  │  │ /api/v1/agent/*  │  │ /api/ui/*        │  │ /ws/events      │  │   │
│  │  │ Agent Ingestion  │  │ UI Endpoints     │  │ WebSocket       │  │   │
│  │  └────────┬─────────┘  └────────┬─────────┘  └────────┬────────┘  │   │
│  └───────────┼─────────────────────┼─────────────────────┼────────────┘   │
│              │                     │                     │                 │
│  ┌───────────┼─────────────────────┼─────────────────────┼────────────┐   │
│  │           ▼                     ▼                     ▼             │   │
│  │  ┌─────────────────────────────────────────────────────────────┐  │   │
│  │  │                     Core Services                            │  │   │
│  │  │  ┌────────────────┐  ┌────────────────┐  ┌───────────────┐ │  │   │
│  │  │  │ Feature        │  │ Container      │  │ Training      │ │  │   │
│  │  │  │ Extractor      │  │ View Service   │  │ Service       │ │  │   │
│  │  │  │ (594-dim vec)  │  │                │  │               │ │  │   │
│  │  │  └────────────────┘  └────────────────┘  └───────────────┘ │  │   │
│  │  └─────────────────────────────────────────────────────────────┘  │   │
│  │                                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────┐  │   │
│  │  │                         Ports                                │  │   │
│  │  │  ┌─────────────┐ ┌────────────┐ ┌──────────────┐ ┌────────┐│  │   │
│  │  │  │ Anomaly     │ │ Triage     │ │ Policy       │ │ Agent  ││  │   │
│  │  │  │ Detection   │ │ Port       │ │ Generator    │ │ Control││  │   │
│  │  │  │ Port        │ │            │ │ Port         │ │ Port   ││  │   │
│  │  │  └──────┬──────┘ └─────┬──────┘ └──────┬───────┘ └───┬────┘│  │   │
│  │  └─────────┼──────────────┼───────────────┼─────────────┼──────┘  │   │
│  │            │              │               │             │          │   │
│  │  ┌─────────┼──────────────┼───────────────┼─────────────┼──────┐  │   │
│  │  │         ▼              ▼               ▼             ▼       │  │   │
│  │  │                     Adapters                                 │  │   │
│  │  │  ┌─────────────┐ ┌────────────┐ ┌──────────────┐ ┌────────┐│  │   │
│  │  │  │ Hybrid      │ │ Gemini     │ │ Default      │ │ HTTP   ││  │   │
│  │  │  │ Anomaly     │ │ Triage     │ │ Policy       │ │ Agent  ││  │   │
│  │  │  │ Adapter     │ │ Adapter    │ │ Generator    │ │ Adapter││  │   │
│  │  │  └──────┬──────┘ └─────┬──────┘ └──────────────┘ └───┬────┘│  │   │
│  │  └─────────┼──────────────┼───────────────────────────────┼────┘  │   │
│  │            │              │                               │        │   │
│  └────────────┼──────────────┼───────────────────────────────┼────────┘   │
│               │              │                               │            │
│               ▼              ▼                               ▼            │
│        ┌──────────┐   ┌──────────┐                   ┌──────────┐        │
│        │ML Service│   │ Gemini   │                   │  Agent   │        │
│        │ :8081    │   │ API      │                   │  :8082   │        │
│        └──────────┘   └──────────┘                   └──────────┘        │
└────────────────────────────────────────────────────────────────────────────┘
```

**Module Structure:**

| Module | Purpose |
|--------|---------|
| `core` | Main application, controllers, services, repositories |
| `anomaly-engine` | Isolation Forest adapters (in-process, remote, hybrid) |
| `triage-service` | LLM triage adapter (Gemini) |
| `cicd-integration` | GitHub/GitLab change context |
| `policy-engine` | Seccomp/AppArmor policy generation |
| `contracts` | Shared DTOs and models |
| `ui-frontend` | React SPA |

**Port Interfaces:**

```java
// Anomaly Detection
public interface AnomalyDetectionPort {
    AnomalyScore scoreWindow(String containerId, FeatureVector fv);
    void trainModel(String containerId, List<FeatureVector> data, TrainingContext ctx);
    ModelMeta getModelMeta(String containerId);
}

// Triage
public interface TriagePort {
    TriageResult triage(AnomalyWindow window, ChangeContext context);
}

// Policy Generation
public interface PolicyGeneratorPort {
    Policy generatePolicy(AnomalyWindow window, TriageResult triage);
}

// Agent Control
public interface AgentControlPort {
    void requestLongDump(String agentId, String containerId, LongDumpRequest req);
    void applyPolicy(String agentId, String containerId, Policy policy);
}
```

---

### 2.3 ML Service (Python)

**Location:** `ml-service/`

Optional Python microservice providing real Isolation Forest models.

```
┌────────────────────────────────────────────────────────────────────────┐
│                           ML Service (FastAPI)                          │
│                                                                        │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │                         REST API                                │   │
│  │  POST /api/ml/score     - Score feature vector                 │   │
│  │  POST /api/ml/train     - Train model                          │   │
│  │  GET  /api/ml/models/*  - Model metadata                       │   │
│  │  GET  /health           - Health check                         │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                                                                        │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │                      Model Registry                             │   │
│  │  ┌──────────────────────────────────────────────────────────┐ │   │
│  │  │                 Per-Container Models                      │ │   │
│  │  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐        │ │   │
│  │  │  │ Container A │ │ Container B │ │ Container N │        │ │   │
│  │  │  │ IF Model    │ │ IF Model    │ │ IF Model    │        │ │   │
│  │  │  │ v2          │ │ v1          │ │ v3          │        │ │   │
│  │  │  └─────────────┘ └─────────────┘ └─────────────┘        │ │   │
│  │  └──────────────────────────────────────────────────────────┘ │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                                                                        │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │                   Isolation Forest (scikit-learn)               │   │
│  │  • n_estimators: 100                                           │   │
│  │  • contamination: 0.1                                          │   │
│  │  • max_samples: 256                                            │   │
│  │  • Feature dimension: 594                                       │   │
│  └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 2.4 Hybrid Anomaly Detection

The server uses a hybrid approach for anomaly detection:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      HybridAnomalyAdapter                                │
│                                                                         │
│   scoreWindow(containerId, featureVector)                               │
│                      │                                                   │
│                      ▼                                                   │
│              ┌───────────────┐                                          │
│              │ ML Service    │                                          │
│              │ Available?    │                                          │
│              └───────┬───────┘                                          │
│                      │                                                   │
│          ┌───────────┴───────────┐                                      │
│          │ YES                   │ NO                                   │
│          ▼                       ▼                                      │
│   ┌──────────────────┐   ┌──────────────────────────────┐              │
│   │ RemoteMlAdapter  │   │ InProcessIsolationForest     │              │
│   │ → POST :8081     │   │ Adapter (heuristic-based)    │              │
│   │   /api/ml/score  │   │                              │              │
│   └────────┬─────────┘   └──────────────┬───────────────┘              │
│            │                            │                               │
│            │  ┌─────────────────────────┘                               │
│            │  │  (fallback on failure)                                  │
│            ▼  ▼                                                         │
│   ┌──────────────────┐                                                  │
│   │ AnomalyScore     │                                                  │
│   │ {score, anomalous}│                                                  │
│   └──────────────────┘                                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

**Configuration:**

```yaml
# application.yml
deepkernel:
  anomaly:
    mode: HYBRID  # LOCAL | REMOTE | HYBRID
  ml-service:
    url: http://localhost:8081
```

---

## 3. Data Flow

### 3.1 Normal Monitoring Flow

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         Normal Monitoring Flow                              │
│                                                                            │
│  Container                  Agent              Server           Frontend   │
│     │                         │                   │                │       │
│     │ syscall                 │                   │                │       │
│     │─────────────────────────▶                   │                │       │
│     │                         │                   │                │       │
│     │              eBPF captures                  │                │       │
│     │              buffers events                 │                │       │
│     │                         │                   │                │       │
│     │              every 5 seconds                │                │       │
│     │                         │                   │                │       │
│     │                         │ POST /api/v1/     │                │       │
│     │                         │ agent/windows     │                │       │
│     │                         │──────────────────▶│                │       │
│     │                         │                   │                │       │
│     │                         │                   │ Extract        │       │
│     │                         │                   │ 594-dim        │       │
│     │                         │                   │ features       │       │
│     │                         │                   │                │       │
│     │                         │                   │ Score with     │       │
│     │                         │                   │ Isolation      │       │
│     │                         │                   │ Forest         │       │
│     │                         │                   │                │       │
│     │                         │                   │ Triage with    │       │
│     │                         │                   │ LLM (if        │       │
│     │                         │                   │ anomalous)     │       │
│     │                         │                   │                │       │
│     │                         │                   │ WS /topic/     │       │
│     │                         │                   │ events         │       │
│     │                         │                   │───────────────▶│       │
│     │                         │                   │                │       │
│     │                         │  202 Accepted     │                │ Update│
│     │                         │◀──────────────────│                │ UI    │
│     │                         │                   │                │       │
└────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Threat Detection & Policy Enforcement Flow

```
┌────────────────────────────────────────────────────────────────────────────┐
│                      Threat Detection Flow                                  │
│                                                                            │
│  Container    Agent        Server        ML       LLM       Frontend       │
│     │           │            │           │         │           │           │
│     │ malicious │            │           │         │           │           │
│     │ syscalls  │            │           │         │           │           │
│     │──────────▶│            │           │         │           │           │
│     │           │            │           │         │           │           │
│     │           │ window     │           │         │           │           │
│     │           │───────────▶│           │         │           │           │
│     │           │            │           │         │           │           │
│     │           │            │ score     │         │           │           │
│     │           │            │──────────▶│         │           │           │
│     │           │            │           │         │           │           │
│     │           │            │◀──────────│         │           │           │
│     │           │            │ ANOMALOUS │         │           │           │
│     │           │            │ (-0.8)    │         │           │           │
│     │           │            │           │         │           │           │
│     │           │            │ triage    │         │           │           │
│     │           │            │──────────────────▶│           │           │
│     │           │            │                     │           │           │
│     │           │            │◀────────────────────│           │           │
│     │           │            │ THREAT, risk=0.9   │           │           │
│     │           │            │ "Suspicious connect│           │           │
│     │           │            │  to high port"     │           │           │
│     │           │            │                     │           │           │
│     │           │            │ Generate Seccomp   │           │           │
│     │           │            │ policy             │           │           │
│     │           │            │                     │           │           │
│     │           │ POST       │                     │           │           │
│     │           │ /policies  │                     │           │           │
│     │           │◀───────────│                     │           │           │
│     │           │            │                     │           │           │
│     │  Apply    │            │                     │           │           │
│     │  Seccomp  │            │                     │           │           │
│     │◀──────────│            │                     │           │           │
│     │           │            │                     │           │           │
│     │           │            │ WS: POLICY_APPLIED │           │           │
│     │           │            │────────────────────────────────▶│           │
│     │           │            │                     │           │ Alert!   │
│     │           │            │                     │           │           │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Feature Engineering

The 594-dimensional feature vector is computed from each 5-second syscall window:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Feature Vector (594 dimensions)                           │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Markov Transition Matrix (K=24 syscall classes)         [0-575]    │   │
│  │ 24 × 24 = 576 features                                             │   │
│  │                                                                     │   │
│  │ Syscall Classes:                                                   │   │
│  │  0: execve          8: sendto/sendmsg    16: getuid/setuid        │   │
│  │  1: open/openat     9: recvfrom/recvmsg  17: setrlimit            │   │
│  │  2: read           10: socket/bind/listen 18: ptrace              │   │
│  │  3: write          11: fork/clone        19: mount/umount         │   │
│  │  4: close          12: mmap/mprotect     20: brk                  │   │
│  │  5: stat/fstat     13: chmod/fchmod      21: getdents             │   │
│  │  6: connect        14: unlink/rmdir      22: nanosleep            │   │
│  │  7: accept         15: rename            23: OTHER                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Statistical Features                                    [576-581]  │   │
│  │  576: uniqueTwoGrams  - Count of unique syscall pairs              │   │
│  │  577: entropy         - Shannon entropy of transitions             │   │
│  │  578: fileRatio       - Proportion of FILE operations              │   │
│  │  579: netRatio        - Proportion of NET operations               │   │
│  │  580: durationSec     - Window duration in seconds                 │   │
│  │  581: meanInterArrival - Mean time between syscalls               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Argument Bucket Features (12 buckets)                   [582-593]  │   │
│  │  Normalized counts of syscall arguments by class + bucket          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Network Topology

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Network Topology                                   │
│                                                                             │
│                         ┌───────────────────┐                               │
│                         │    Frontend       │                               │
│                         │    :5173          │                               │
│                         └─────────┬─────────┘                               │
│                                   │ HTTP/WS                                 │
│                                   ▼                                         │
│  ┌─────────────┐          ┌───────────────────┐          ┌───────────────┐ │
│  │   Agent     │─────────▶│    Server         │◀────────▶│  ML Service   │ │
│  │   :8082     │  HTTP    │    :9090          │  HTTP    │  :8081        │ │
│  │   (inbound) │          │                   │          │               │ │
│  └──────┬──────┘          └─────────┬─────────┘          └───────────────┘ │
│         │                           │                                       │
│         │                           │ (optional)                            │
│         │                           ▼                                       │
│         │                   ┌───────────────────┐                          │
│         │                   │  Gemini API       │                          │
│         │                   │  (external)       │                          │
│         │                   └───────────────────┘                          │
│         │                                                                   │
│         ▼                                                                   │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                    Docker Containers                                  │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐             │  │
│  │  │ App A    │  │ App B    │  │ App C    │  │ App D    │             │  │
│  │  │ :8080    │  │ :3000    │  │ :5000    │  │ ...      │             │  │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘             │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Port Summary:
┌──────────┬─────────┬──────────────────────────────────────────────────────┐
│ Service  │ Port    │ Purpose                                              │
├──────────┼─────────┼──────────────────────────────────────────────────────┤
│ Server   │ 9090    │ REST API + WebSocket for agent & frontend           │
│ Agent    │ 8082    │ HTTP server for commands from server                 │
│ ML Svc   │ 8081    │ Isolation Forest scoring & training                 │
│ Frontend │ 5173    │ React dev server (Vite)                             │
└──────────┴─────────┴──────────────────────────────────────────────────────┘
```

---

## 6. Configuration Reference

### 6.1 Agent Configuration

**Environment Variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `DK_AGENT_ID` | `node-1` | Agent identifier |
| `DK_SERVER_URL` | `http://localhost:9090` | Server URL |
| `DK_AGENT_PORT` | `8082` | Agent HTTP server port |
| `DK_SHORT_WINDOW_SEC` | `5` | Window duration |
| `DK_DOCKER_SOCKET` | `/var/run/docker.sock` | Docker socket path |
| `DK_CONTAINER_FILTER` | `.*` | Regex to filter containers |

### 6.2 Server Configuration

**`application.yml`:**

```yaml
server:
  port: 9090

deepkernel:
  agent:
    base-url: http://localhost:8082
  anomaly:
    mode: HYBRID  # LOCAL | REMOTE | HYBRID
  ml-service:
    url: http://localhost:8081
  gemini:
    api-key: ${GEMINI_API_KEY:}
    model: gemini-pro
  ui:
    origin: http://localhost:5173
```

### 6.3 ML Service Configuration

**Environment Variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `ML_SERVICE_PORT` | `8081` | Service port |
| `FEATURE_VECTOR_DIM` | `594` | Expected feature dimensions |
| `ISOLATION_FOREST_N_ESTIMATORS` | `100` | Trees in forest |
| `ISOLATION_FOREST_CONTAMINATION` | `0.1` | Expected anomaly ratio |
| `ANOMALY_THRESHOLD` | `-0.5` | Score threshold |
| `MODEL_STORAGE_PATH` | `./models` | Model persistence directory |

---

## 7. API Reference

### 7.1 Agent → Server

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/v1/agent/windows` | POST | Submit syscall window |
| `/api/v1/agent/dump-complete` | POST | Notify long dump completion |

### 7.2 Server → Agent

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/long-dump-requests` | POST | Request extended monitoring |
| `/policies` | POST | Apply security policy |
| `/health` | GET | Health check |

### 7.3 Server → ML Service

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/ml/score` | POST | Score feature vector |
| `/api/ml/train` | POST | Train model |
| `/api/ml/models/{id}` | GET | Get model metadata |
| `/health` | GET | Health check |

### 7.4 Frontend → Server

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/ui/containers` | GET | List containers |
| `/api/ui/containers/{id}/models` | GET | Container models |
| `/api/ui/events` | GET | Recent events |
| `/api/ui/train/{id}` | POST | Trigger training |
| `/ws/events` | WS | Real-time event stream |

---

## 8. Security Considerations

### 8.1 Agent Security

- Requires `CAP_BPF`, `CAP_PERFMON` capabilities
- Access to `/var/run/docker.sock` for container info
- Runs as root (required for eBPF)
- Should be deployed as privileged DaemonSet in K8s

### 8.2 Policy Enforcement

- Seccomp profiles are validated before application
- Policies are conservative (deny specific syscalls, not allow-lists)
- Demo mode: Deny high ports only (not all connect)

### 8.3 Network Security

- All internal communication over HTTP (demo mode)
- Production should use TLS for all connections
- CORS configured for frontend origin

---

## 9. Deployment Modes

### 9.1 Demo Mode (Single Host)

All components run on the same machine:

```bash
# Terminal 1: ML Service
cd ml-service && python -m src.main

# Terminal 2: Server
cd server && ./gradlew :core:bootRun

# Terminal 3: Agent (Linux host or VM)
cd agent/build && sudo ./deepkernel-agent

# Terminal 4: Sample App
cd demo-apps/bachat-bank && docker-compose up

# Terminal 5: Frontend
cd server/ui-frontend && npm run dev
```

### 9.2 Production Mode (Kubernetes)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Kubernetes Cluster                                   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ DaemonSet: deepkernel-agent (one per node)                          │   │
│  │  - Privileged container                                             │   │
│  │  - hostPID: true                                                    │   │
│  │  - Mounts: /sys, /var/run/docker.sock                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Deployment: deepkernel-server                                       │   │
│  │  - Replicas: 2+                                                     │   │
│  │  - Service: ClusterIP :9090                                         │   │
│  │  - Ingress: deepkernel.example.com                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Deployment: deepkernel-ml                                           │   │
│  │  - Replicas: 2+                                                     │   │
│  │  - Service: ClusterIP :8081                                         │   │
│  │  - PVC: model-storage                                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ConfigMap: deepkernel-config                                        │   │
│  │ Secret: deepkernel-secrets (API keys)                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Technology Stack Summary

| Layer | Technology | Version |
|-------|------------|---------|
| **Agent** | C++17 + libbpf | - |
| **eBPF** | BTF-enabled kernel | 5.8+ |
| **Server** | Spring Boot | 3.2.0 |
| **ML Service** | FastAPI + scikit-learn | 0.115.0 / 1.3.2 |
| **Frontend** | React + Tailwind | 18.x |
| **Build** | CMake / Gradle / pip | - |
| **Containers** | Docker | 20.10+ |

---

## 11. Repository Structure

```
deepkernel-mono/
├── MASTER_PLAN.md              # Original design document
├── ARCHITECTURE_DKO.md         # This document
├── README.md                   # Quick start guide
│
├── agent/                      # C++ eBPF agent
│   ├── src/                    # User-space code
│   ├── include/                # Headers
│   ├── ebpf/                   # eBPF programs
│   ├── config/                 # Sample config
│   └── CMakeLists.txt
│
├── server/                     # Java Spring Boot
│   ├── core/                   # Main application
│   ├── anomaly-engine/         # ML adapters
│   ├── triage-service/         # LLM triage
│   ├── cicd-integration/       # Change context
│   ├── policy-engine/          # Policy generation
│   ├── contracts/              # Shared DTOs
│   ├── ui-frontend/            # React SPA
│   └── build.gradle
│
├── ml-service/                 # Python ML service
│   ├── src/                    # FastAPI app
│   ├── tests/                  # Tests
│   ├── requirements.txt
│   └── Dockerfile
│
├── demo-apps/                  # Sample applications
│   └── bachat-bank/            # Demo banking app
│
└── docs/                       # Additional documentation
    ├── API_SPEC.md
    └── FEATURES.md
```

---

## 12. Glossary

| Term | Definition |
|------|------------|
| **Anomaly Score** | Isolation Forest output; negative = more anomalous |
| **Arg Class** | Syscall argument category (FILE, NET, PROC, MEM, OTHER) |
| **Arg Bucket** | Quantized syscall argument value (0-3) |
| **Feature Vector** | 594-dimensional representation of syscall window |
| **Isolation Forest** | Unsupervised ML algorithm for anomaly detection |
| **Long Dump** | Extended (15-30 min) syscall capture for training |
| **Short Window** | 5-second syscall batch for real-time scoring |
| **Triage** | LLM-powered analysis of anomalies with context |
| **Verdict** | SAFE, THREAT, or UNKNOWN classification |

---

*Document generated for DeepKernel Platform v0.1.0*

