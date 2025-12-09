# DeepKernel – Master Plan

**Role:** Principal Software Engineer
**Project Name:** `DeepKernel` – Intelligent Runtime Security for Any Workload

This document is the **single source of truth** for implementing DeepKernel end-to-end in one repository. It defines:

* Architecture and components (agent, server, ML, LLM, UI)
* Data structures and contracts (JSON/binary)
* Feature engineering for Isolation Forest on syscall flows
* Continuous learning loop and CI/CD integration
* Frontend views and APIs

The goal is that an AI coding agent (Codex, Cursor, Aider, Antigravity) can read this file and generate the entire implementation.

---

## 1. Vision & High-Level Flow

DeepKernel is a runtime security platform that:

1. Uses **eBPF** agents to capture per-container syscall traces.
2. Builds **per-container Isolation Forest models** on syscall **patterns** (flows), not just counts.
3. For each 5-second window:

   * Scores it for anomaly.
   * Correlates with **recent code changes** from CI/CD.
   * Calls an **LLM** (Gemini or equivalent) for triage.
4. If verdict is **SAFE**:

   * Treats the behavior as acceptable.
   * Requests a **new long dump**, retrains the model (continuous learning).
5. If verdict is **THREAT**:

   * Generates **blocking policies** (Seccomp/AppArmor/NetworkPolicy).
   * Sends policies back to the agent for enforcement.
6. Frontend shows:

   * Which containers are monitored.
   * Per-container anomaly verdicts in near real time (~10s).
   * Model updates on SAFE, and policy enforcement on THREAT.

---

## 2. Repository Layout

Single repo with multiple modules:

```text
deepkernel/
  MASTER_PLAN.md

  agent/                      # deepkernel-agent (C++/libbpf)
    src/
    include/
    ebpf/
    CMakeLists.txt
    config/

  server/                     # deepkernel-server (Java Spring Boot)
    core/                     # ingest, orchestration, APIs
    anomaly-engine/           # Isolation Forest integration
    triage-service/           # LLM triage client (Gemini)
    cicd-integration/         # Git/GitHub/GitLab integration
    policy-engine/            # seccomp/app-armor/netpolicy generator
    ui-frontend/              # React/Tailwind frontend (SPA)
    contracts/                # shared DTOs/JSON schemas
    build.gradle / pom.xml

  ml-service/                 # optional Python ML service (if not embedded in server)
    src/
    requirements.txt

  contracts/                  # language-agnostic contracts (JSON schemas / protobuf)
    agent/
    server/
    ui/
    cicd/

  infra/                      # deployment configs
    k8s/
    docker/
    helm/

  docs/
    API_SPEC.md
    FEATURES.md               # details of feature vectors
    UI_DESIGN.md              # optional extracted UI doc (can be generated from this)
```

You can choose to keep ML embedded in Java or use `ml-service` as a separate Python microservice. This plan assumes server-core orchestrates everything via clearly defined ports and adapters.

---

## 3. Domain Model Overview

Key domain entities (conceptual):

* **Container**

  * `id` (string, stable identifier: `namespace/name`, or image+tag)
  * `namespace`
  * `node`
  * `status`
  * `agentConnected` (bool)
  * `modelStatus` (UNTRAINED, READY, TRAINING, ERROR)

* **Agent**

  * Runs per node.
  * Attached to cgroups / containers via eBPF.
  * Sends syscall **windows** and **long dumps**.
  * Receives and enforces policies.

* **ModelVersion**

  * `modelId`
  * `containerId`
  * `version` (int)
  * `featureVersion` (e.g. `"v1"`)
  * `trainedAt`
  * `status`
  * `metrics` (training stats, feature dimension)

* **AnomalyWindow**

  * `id`
  * `containerId`
  * `windowStart`
  * `windowEnd`
  * `mlScore`
  * `isAnomalous`
  * `triageStatus` (PENDING, SAFE, THREAT)
  * `triageResultId` (foreign key)

* **TriageResult**

  * `id`
  * `containerId`
  * `windowId`
  * `riskScore` (0–1)
  * `verdict` (SAFE / THREAT / UNKNOWN)
  * `explanation` (from LLM)
  * `llmResponseRaw` (optional, JSON blob)

* **Policy**

  * `id`
  * `containerId`
  * `type` (SECCOMP, APPARMOR, NETWORK_POLICY)
  * `spec` (YAML/JSON payload)
  * `appliedAt`
  * `node`
  * `status` (PENDING, APPLIED, FAILED)

* **DeploymentEvent** (from CI/CD)

  * `id`
  * `containerId`
  * `commitId`
  * `pipelineId`
  * `changedFiles[]`
  * `createdAt`

---

## 4. deepkernel-agent Design

Language: **C++17**
Runs on each node as a **DaemonSet** in Kubernetes.

### 4.1 Responsibilities

* Attach eBPF programs to kernel (sys_enter/sys_exit, tracepoints).
* Map raw kernel events to our compact syscall event structure.
* Track events per **container** (using cgroups / container ID).
* Maintain in-memory buffers for:

  * **Short windows** (~5s).
  * **Long dumps** (15–30 minutes).
* Periodically:

  * Send 5-second windows to server.
  * On request, write long dump to disk and notify server.
* Receive policies from server and enforce them:

  * Configure **Seccomp profiles** for target containers.
  * Optionally update AppArmor/NetworkPolicy via a local sidecar / kube API adapter.

### 4.2 Agent Configuration

Config file (`agent/config/agent-config.yaml`):

```yaml
agentId: "node-1"
serverUrl: "https://deepkernel-server:8443"
nodeName: "worker-01"

windows:
  shortWindowSec: 5
  sendIntervalSec: 5       # or add jitter/backoff
  minEventsPerWindow: 20

longDump:
  defaultDurationSec: 1200 # 20 minutes
  dumpDir: "/var/lib/deepkernel/dumps"

containers:
  monitor:
    - "prod/billing-api"
    - "prod/payments-svc"
    - "staging/inventory-svc"
  includeNamespaceRegex: "prod|staging"
```

### 4.3 eBPF Event Struct (kernel → user space)

In `agent/ebpf/deepkernel.bpf.c`:

```c
typedef struct {
    __u64 ts_ns;             // monotonic timestamp
    __u32 pid;
    __u32 tid;
    __u32 cgroup_id;         // for mapping to container
    __u16 syscall_id;        // global enum, 0..M-1
    __u8  arg_class;         // FILE / NET / PROC / MEM / OTHER
    __u8  arg_bucket;        // bucket within class
} dk_syscall_event_t;
```

These are sent to user space via perf buffer / ring buffer.

### 4.4 User-Space Event Representation

In `agent/include/syscall_event.h`:

```cpp
struct SyscallEvent {
    uint64_t tsNs;
    uint32_t pid;
    uint32_t tid;
    uint64_t cgroupId;
    uint16_t syscallId;
    uint8_t  argClass;
    uint8_t  argBucket;
};
```

### 4.5 Per-Container Buffers

In `agent/src/buffer_manager.h`:

```cpp
struct ContainerBuffer {
    std::string containerId;      // "namespace/name"
    uint64_t lastTsNs;

    // For short (5s) windows
    std::vector<SyscallEvent> currentShortWindow;

    // For long dumps (in-memory staging or file streaming)
    bool isLongDumpActive;
    uint64_t longDumpStartTsNs;
    std::ofstream longDumpStream; // binary file stream
};
```

---

## 5. Agent ⇄ Server Contracts

### 5.1 Compact Trace Record Structure (for long dump + short windows)

Binary record (storage and optionally wire):

```c
typedef struct {
    uint32_t delta_ts_us;   // ts_ns - previous_ts_ns in microseconds
    uint16_t syscall_id;
    uint8_t  arg_class;
    uint8_t  arg_bucket;
} dk_trace_record_t;
```

Binary header:

```c
typedef struct {
    uint32_t version;                // e.g. 1
    uint32_t syscall_vocab_size;     // M
    char     container_id[64];       // "prod/billing-api"
    uint64_t start_ts_ns;
} dk_trace_header_t;
```

File layout:

```text
[dk_trace_header_t][record1][record2]...[recordN]
```

### 5.2 Short Window Payload (JSON over HTTP/gRPC)

Endpoint: `POST /api/v1/agent/windows`

```json
{
  "version": 1,
  "agent_id": "worker-01",
  "container_id": "prod/billing-api",
  "window_start_ts_ns": 1733768415000000000,
  "records": [
    { "delta_ts_us": 0,   "syscall_id": 0,  "arg_class": 2, "arg_bucket": 1 },
    { "delta_ts_us": 300, "syscall_id": 5,  "arg_class": 1, "arg_bucket": 3 }
  ]
}
```

Server reconstructs absolute timestamps from `window_start_ts_ns` + `delta_ts_us` and maps `syscall_id` to alphabet indices.

### 5.3 Long Dump Control Protocol

**Request** (server → agent, gRPC or HTTP):

`POST /api/v1/agent/{agentId}/containers/{containerId}/long-dump-requests`

```json
{
  "duration_sec": 1200,
  "reason": "INITIAL_TRAINING"  // or "CONTINUOUS_LEARNING"
}
```

**Response** (agent acknowledges async, then later notifies completion):

`POST /api/v1/agent/long-dump-complete`

```json
{
  "agent_id": "worker-01",
  "container_id": "prod/billing-api",
  "dump_path": "/var/lib/deepkernel/dumps/billing-api-20251209-120000.dkdump",
  "start_ts_ns": 1733768400000000000,
  "duration_sec": 1200
}
```

Server then fetches file either:

* Via node-local sidecar (e.g. DaemonSet + hostPath).
* Or via SCP-like mechanism / shared volume.

### 5.4 Policy Enforcement Command (server → agent)

`POST /api/v1/agent/{agentId}/containers/{containerId}/policies`

```json
{
  "policy_id": "policy-uuid-1234",
  "type": "SECCOMP",
  "version": 1,
  "spec": {
    "profile_name": "dk-deny-inet-high-ports",
    "syscalls": [
      { "name": "connect", "action": "SCMP_ACT_ERRNO", "args": [ /* optional */ ] }
    ]
  }
}
```

Agent applies the policy and responds:

```json
{
  "policy_id": "policy-uuid-1234",
  "container_id": "prod/billing-api",
  "status": "APPLIED",
  "applied_at_ts_ns": 1733768417000000000
}
```

---

## 6. deepkernel-server Design

Language: **Java (Spring Boot)**

### 6.1 Core Modules

1. **Ingestion Service**

   * Exposes `/agent/windows` and `/agent/long-dump-complete`.
   * Validates and queues records to Kafka / internal queue.

2. **Feature Extractor (Anomaly Engine Port)**

   * Converts syscall windows into feature vectors.
   * Uses shared `FEATURES.md` spec (see Section 7).

3. **Anomaly Engine**

   * Implements per-container Isolation Forest models.
   * Ports and adapters:

     * Port: `AnomalyDetectionPort` (interface).
     * Adapters:

       * `InProcessIsolationForestAdapter` (using a Java ML lib).
       * `RemoteMLServiceAdapter` (HTTP/gRPC to `ml-service` in Python).

4. **Model Registry**

   * Stores `ModelVersion` metadata in DB.
   * Persists model artifacts (on disk or object store).
   * Provides `getModel(containerId)` and `updateModel(containerId, model)`.

5. **Triage Service**

   * Port: `TriagePort`.
   * Adapters:

     * `GeminiHttpAdapter` (production).
     * `MockTriageAdapter` (dev).

6. **CI/CD Integration**

   * Port: `ChangeContextPort`.
   * Adapters:

     * `GitHubAdapter` (calls GitHub REST API to get commits / diffs).
     * `LocalRepoAdapter` (dev / monolith mode).

7. **Policy Engine**

   * Generates Seccomp/AppArmor/NetworkPolicy specs from anomaly windows.
   * Port: `PolicyGeneratorPort`.
   * Adapters for:

     * JSON/YAML generation.
     * Optional enforcement via K8s API when running in-cluster.

8. **Agent Control Service**

   * Sends commands to agent (long dump requests, policy enforcement).
   * Port: `AgentControlPort`.
   * Adapters:

     * `HttpAgentAdapter` (agent REST API).
     * `MockAgentAdapter`.

9. **API Gateway + WebSocket Hub**

   * REST APIs for UI.
   * WebSocket channels for live event streaming.

10. **UI Frontend**

* React SPA served by server (or separate).

### 6.2 Consumer-Owned Interfaces (Ports)

Example: `AnomalyDetectionPort` (owned by server core):

```java
public interface AnomalyDetectionPort {
    AnomalyScore scoreWindow(String containerId, FeatureVector fv);

    void trainModel(String containerId, List<FeatureVector> trainingData, TrainingContext ctx);

    ModelMeta getModelMeta(String containerId);
}
```

Adapters:

* `InProcessIsolationForestAdapter` implements this using a Java ML lib.
* `RemoteMlAdapter` calls Python `ml-service`.

Factories choose implementation based on env:

```java
public class AnomalyEngineFactory {
    public static AnomalyDetectionPort create() {
        String mode = System.getenv("ANOMALY_ENGINE_MODE");
        if ("REMOTE".equalsIgnoreCase(mode)) {
            return new RemoteMlAdapter(...);
        }
        return new InProcessIsolationForestAdapter(...);
    }
}
```

Same pattern for:

* `TriagePort`
* `ChangeContextPort`
* `AgentControlPort`
* `PolicyGeneratorPort`

---

## 7. Feature Engineering for Isolation Forest

Defined in `docs/FEATURES.md` but summarized here for implementation.

### 7.1 Core Idea

* Detection unit: **window** of syscalls per container (approx. 5 seconds).
* Baseline training:

  * One **long dump** of 15–30 min yields many overlapping windows.
  * Each window → feature vector.
* Online scoring:

  * Every 5s window from agent → feature vector → Isolation Forest score.
* We model **patterns of syscalls** via a **transition matrix**, not just counts.

### 7.2 Syscall Alphabet

Global alphabet of size `K` (e.g. 24):

```text
0  execve
1  open/openat/creat
2  read
3  write
4  close
5  stat/fstat/lstat
6  connect
7  accept
8  sendto/sendmsg
9  recvfrom/recvmsg
10 socket/bind/listen
11 fork/clone/vfork
12 mmap/mprotect
13 chmod/chown/umask
14 unlink/rmdir
15 rename
16 getuid/setuid
17 prlimit/setrlimit
18 ptrace
19 mount/umount
20 brk/sbrk
21 getdents/readdir
22 nanosleep/clock_nanosleep
23 OTHER
```

A mapping function:

```java
int mapSyscallToAlphabet(int syscallIdRaw);
```

### 7.3 Windowing

For training (long dump):

* `T_window = 5s`, `T_stride = 2.5s` (overlap).
* Reconstruct `tsNs` using `start_ts_ns` + cumulative `delta_ts_us`.
* For each `[t, t+T_window)` → collect records.

For inference (short dumps):

* Use the entire 5s payload as the window.

### 7.4 Features

Let `R` be the list of records in one window.

#### 7.4.1 Transition Matrix (flow pattern)

For each consecutive pair `(prev, curr)` in `R`:

```java
int prevId = mapSyscallToAlphabet(prev.syscallId);
int currId = mapSyscallToAlphabet(curr.syscallId);

transitions[prevId][currId] += 1;
outgoingCount[prevId] += 1;
```

Normalize rows:

```java
for i in 0..K-1:
  if outgoingCount[i] > 0:
    for j in 0..K-1:
      transitions[i][j] /= outgoingCount[i];
```

Flatten to vector `vMarkov` of length `K*K` (e.g. 576).

#### 7.4.2 Pattern / Variety Features

* `uniqueTwoGrams` = number of `(prevId, currId)` with count > 0.
* `entropy` of transition distribution (how predictable behavior is):

```java
double entropy = 0.0;
int totalTransitions = sum(outgoingCount);
for (i, j):
  double count = transitions[i][j] * outgoingCount[i]; // revert to count
  if (count > 0):
    double p = count / totalTransitions;
    entropy -= p * log(p);
```

#### 7.4.3 Category Ratios

From `argClass` in each record:

* `fileOps`, `netOps`, `procOps`, `otherOps`.
* Ratios: `fileRatio`, `netRatio`, `procRatio` (others implied).

#### 7.4.4 Timing Features

* `durationSec` = `(lastTs - firstTs) / 1e9`.
* `meanInterArrival` = `durationSec / numRecords` (if > 0).

#### 7.4.5 Argument Bucket Features

Define `BUCKETS` total buckets across all `argClass` types, e.g.:

* FILE: `CONFIG_DIR`, `LOG_DIR`, `TMP`, `BIN_DIR`, `ETC`, `OTHER`
* NET: `LOOPBACK`, `LAN`, `INTERNET_PRIV_PORT`, `INTERNET_HIGH_PORT`, `UNKNOWN`
* PROC: `APP_BINARY`, `SHELL`, `INTERPRETER`, `OTHER`

Map each `(argClass, argBucket)` to `bucketIndex` in `0..BUCKETS-1`.

Count occurrences; convert to ratios:

```java
float[] vArgs = new float[BUCKETS];
for (i in 0..BUCKETS-1):
  vArgs[i] = (float) bucketCounts[i] / totalRecords;
```

### 7.5 Final Feature Vector Layout

If:

* `K = 24` → `K*K = 576`.
* `F_stats = 4` (uniqueTwoGrams, entropy, fileRatio, netRatio).
* `F_time = 2` (durationSec, meanInterArrival).
* `F_args = 12`.

Then:

```text
FEATURE_DIM = 576 + 4 + 2 + 12 = 594
```

Layout in `float[] features`:

1. `vMarkov[0..575]`
2. `uniqueTwoGrams`
3. `entropy`
4. `fileRatio`
5. `netRatio`
6. `durationSec`
7. `meanInterArrival`
8. `vArgs[0..11]`

This is the vector for training and scoring.

### 7.6 Training Flow (per container)

1. Load long dump file.
2. Slice into windows with `[t, t+T_window)` and stride `T_stride`.
3. For each window with `>= MIN_RECORDS` events (e.g. 50):

   * Extract feature vector.
4. Collect all vectors:

   * Optionally clean by:

     * Train once.
     * Remove top X% most anomalous.
     * Retrain on remaining data.
5. Persist model artifact + `ModelVersion` metadata.

### 7.7 Inference Flow (per 5-second window)

1. Receive short window payload from agent.
2. Convert records → feature vector `fv`.
3. Get container’s model from registry.
4. Compute anomaly score.
5. Create `AnomalyWindow` entry.
6. If score < threshold → mark as anomalous and trigger triage.

---

## 8. Triage and CI/CD Integration

### 8.1 TriagePort Contract

```java
public interface TriagePort {
    TriageResult triage(AnomalyWindow window, ChangeContext changeContext);
}
```

`ChangeContext` comes from CI/CD integration.

### 8.2 ChangeContextPort

```java
public interface ChangeContextPort {
    ChangeContext getChangeContext(String containerId, Instant since);
}
```

`ChangeContext`:

```java
public class ChangeContext {
    String containerId;
    String commitId;
    String repoUrl;
    List<String> changedFiles;
    String diffSummary;    // e.g. unified diff snippet or summarized text
    Instant deployedAt;
}
```

### 8.3 Triage Request to LLM (Gemini-like)

Example payload (server → LLM service):

```json
{
  "container_id": "prod/billing-api",
  "window_start": "2025-12-09T12:04:52Z",
  "ml_score": -0.92,
  "syscall_summary": {
    "top_transitions": [
      ["execve", "openat", 0.3],
      ["openat", "read", 0.4],
      ["read", "connect(INTERNET_HIGH_PORT)", 0.1]
    ],
    "file_ratio": 0.22,
    "net_ratio": 0.35,
    "arg_buckets": {
      "NET_INTERNET_HIGH_PORT": 0.12,
      "FILE_TMP": 0.08
    }
  },
  "change_context": {
    "commit_id": "a9f2dcd",
    "changed_files": [
      "billing/routes/payments.py",
      "billing/net/client.py"
    ],
    "diff_summary": "New outbound calls to payments-svc on port 8443 added."
  }
}
```

LLM response:

```json
{
  "risk_score": 0.83,
  "verdict": "THREAT",
  "explanation": "The anomalous syscall flow includes external connections to high, non-configured ports, which is not explained by the recent code changes.",
  "suggested_actions": [
    "Block connect to INTERNET_HIGH_PORT for this container",
    "Alert security team with snapshot"
  ]
}
```

Server stores `TriageResult` in DB.

---

## 9. Policy Generation

`PolicyGeneratorPort`:

```java
public interface PolicyGeneratorPort {
    PolicySpec generatePolicy(AnomalyWindow window, TriageResult triageResult);
}
```

`PolicySpec` example (Seccomp style):

```json
{
  "type": "SECCOMP",
  "profile_name": "dk-deny-inet-high-ports",
  "syscalls": [
    {
      "name": "connect",
      "action": "SCMP_ACT_ERRNO",
      "args": [
        {
          "index": 1,
          "op": "SCMP_CMP_GE",
          "value": 1024
        }
      ]
    }
  ]
}
```

Policy is then passed to `AgentControlPort.applyPolicy(containerId, agentId, policySpec)`.

---

## 10. Frontend Design

Language: **TypeScript + React + Tailwind CSS**
Served by `server/ui-frontend`.

### 10.1 Views

1. **Dashboard / Cluster Overview**

   * Table of containers:

     * `Container`, `Namespace`, `Agent`, `Model Status`, `Last Verdict`, `Last Anomaly Score`, `Last Deploy`.
   * KPI cards:

     * Number of containers monitored.
     * # SAFE vs # THREAT in last hour.
     * # Models READY.

2. **Container Detail View**

   * Header with:

     * Container ID.
     * Status (SAFE/THREAT).
     * Last deployment info.
     * Model version and last training time.
   * Panels:

     * **Anomaly Score Chart** (sparkline).
     * **Latest Verdict Card**:

       * SAFE/THREAT.
       * Score.
       * LLM explanation.
     * **Policy Panel** (if THREAT):

       * Policy type.
       * Applied/failed status.
     * **Model Activity**:

       * Show last N model updates (continuous learning).

3. **Live Event Stream**

   * Real-time log-like feed:

     * window received → score → triage → model updated / policy enforced.
   * Filter by container.

4. **Model Explorer (per container)**

   * List of model versions:

     * version, trainedAt, training data stats.
   * Compare selected versions:

     * highlight changes in feature distribution (optional, textual summary).

### 10.2 Component Tree

Key components:

* `<DashboardPage />`

  * `<ContainerTable />`
  * `<KpiCards />`
* `<ContainerPage />`

  * `<ContainerStatusCard />`
  * `<AnomalyScoreChart />`
  * `<VerdictCard />`
  * `<PolicyCard />`
  * `<ModelActivityList />`
* `<LiveEventsPage />`

  * `<LiveEventStream />`
* `<ModelExplorerPage />`

  * `<ModelVersionList />`
  * `<ModelComparisonPanel />`

### 10.3 Frontend API Contracts

REST:

* `GET /api/ui/containers`
* `GET /api/ui/containers/{id}`
* `GET /api/ui/containers/{id}/models`
* `GET /api/ui/containers/{id}/events?limit=100`
* `POST /api/ui/containers/{id}/request-baseline-dump`

WebSocket:

* `WS /ws/events`
  Event payload example:

```json
{
  "type": "WINDOW_SCORED",
  "timestamp": "2025-12-09T12:04:52Z",
  "container_id": "prod/billing-api",
  "ml_score": -0.44,
  "is_anomalous": false
}
```

* `type: "TRIAGE_RESULT"`
* `type: "POLICY_APPLIED"`
* `type: "MODEL_UPDATED"`

Frontend subscribes to `/ws/events` and updates UI in real time.

---

## 11. End-to-End Flows (Narrative)

### 11.1 Baseline Training Flow

1. Admin registers container(s) to monitor.
2. Server uses `AgentControlPort` to send `REQUEST_LONG_DUMP` for each container.
3. Agent records 15–30 minutes of per-container syscalls.
4. Agent writes `*.dkdump` file and notifies server.
5. Server:

   * Loads dump.
   * Slices into overlapping 5s windows.
   * Extracts feature vectors.
   * Trains Isolation Forest model.
   * Saves model artifact & metadata.
   * Marks `modelStatus = READY`.

### 11.2 Normal SAFE Deployment Flow

1. CI/CD deploys new version; pipeline posts `DeploymentEvent` to server or server polls Git provider.
2. Shortly after deploy, agent continues sending 5s windows.
3. Server scores each window:

   * Slight behavior change → some windows flagged as anomalous.
4. For each anomalous window:

   * `ChangeContextPort` fetches recent code change context.
   * Triage service calls LLM with:

     * syscall flow summary,
     * anomalies,
     * code changes.
   * LLM returns `risk_score < threshold` → verdict SAFE.
5. Server:

   * Marks `AnomalyWindow` as SAFE.
   * Optionally triggers new long dump for continuous learning.
   * Retrains model with new SAFE behavior.
   * Frontend:

     * Shows “SAFE (learned new pattern)” in container detail.
     * Updates model version in UI.

### 11.3 THREAT Flow

1. Attack / malicious behavior triggers unusual syscall sequence:

   * Example: `execve` → `openat /tmp` → `write` → `connect to INTERNET_HIGH_PORT`.
2. Window scored as highly anomalous.
3. Triage:

   * LLM sees no code changes explaining this pattern.
   * Returns `risk_score > 0.7` and verdict THREAT.
4. Policy engine:

   * Generates blocking policy (e.g. deny `connect` to high ports).
5. Agent control:

   * Sends policy to agent.
   * Agent enforces policy via Seccomp / container runtime.
6. Frontend:

   * Live event stream shows THREAT and policy application.
   * Container detail page shows threat verdict, explanation, and enforcement status.

---

## 12. Non-Functional Requirements (High Level)

* Near real-time detection: **<10 seconds** end-to-end from behavior to verdict.
* Per-container models to reduce noise and reflect specific workloads.
* Minimize agent overhead:

  * Batch syscall events.
  * Compress timestamps.
  * Use efficient binary formats on node.
* Security:

  * AuthN/AuthZ for agent→server and server→agent calls.
  * TLS for all network communication.
* Extensibility:

  * Easy to swap Isolation Forest implementation (Java vs Python).
  * Easy to extend UI with more views.
