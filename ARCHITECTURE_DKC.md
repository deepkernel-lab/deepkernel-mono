# DeepKernel Architecture (Implemented Snapshot)

## Overview
DeepKernel provides runtime syscall anomaly detection per container, with triage and policy enforcement. Components:
- **Agent (C++/eBPF)**: captures syscalls per cgroup/container, builds 5s windows, sends to server; supports long dumps and seccomp policy application (best-effort).
- **Server (Spring Boot)**: ingests windows, extracts features, scores (stub IF), triages (stub LLM), generates policies, serves UI REST/WS.
- **UI (React/Vite)**: dashboard, container detail, live events, model explorer; consumes REST/WS.
- **ML Service (FastAPI, optional)**: stubbed; future remote scoring/training.
- **Demo apps**: e.g., Bachat Bank two-service app to showcase THREAT/SAFE flows.

## Logical Architecture
```mermaid
flowchart LR
    Agent[Agent (eBPF+user space)]
    Server[Server (Spring Boot)]
    UI[UI (React SPA)]
    ML[ML Service (optional)]
    DemoApp[Demo Containers (Bachat Bank)]
    Agent -->|/api/v1/agent/windows| Server
    Agent -->|long dump notify| Server
    Server -->|WS /topic/events| UI
    UI -->|REST /api/ui/*| Server
    Server -->|optional scoring| ML
    Agent -->|policy apply (seccomp)| DemoApp
    DemoApp -->|syscalls via cgroup| Agent
```

## Data Flow (Window Scoring)
1. Agent captures sys_enter via eBPF, classifies FILE/NET/PROC/MEM/OTHER.
2. Buffers per container; every ~5s (and min events) builds JSON payload:
   - `version, agent_id, container_id, window_start_ts_ns, records[delta_ts_us, syscall_id, arg_class, arg_bucket]`
3. POST to `POST /api/v1/agent/windows`.
4. Server:
   - Extracts 594-d feature vector (Markov transitions + stats/ratios/time/buckets).
   - Scores via `AnomalyDetectionPort` (stub IF).
   - Creates `AnomalyWindow`, triages via `TriagePort` (stub).
   - If THREAT, generates policy via `PolicyGeneratorPort` and applies via `AgentControlPort` (agent REST).
   - Emits WS events: `WINDOW_SCORED`, `TRIAGE_RESULT`, `POLICY_APPLIED`.
5. UI subscribes `/ws/events` and queries REST for containers/models/events.

## Long Dump & Training (stubbed path)
- Server can request long dump (not fully wired); agent records binary `[header][TraceRecord...]`, notifies `long-dump-complete`.
- Server (future) slices windows, trains IF model per container; updates `ModelRegistryService`.

## Agent Components (implemented)
- eBPF program: tracepoint sys_enter → ring buffer; syscall classification flags.
- User-space runtime:
  - DockerMapper: cgroup/pid → container name via Docker socket (regex patterns incl. compose/podman/k8s hashes).
  - Filtering: regex include (DK_CONTAINER_FILTER).
  - BufferManager: short window + long dump; inactive cleanup (>5m).
  - HTTP client with retry for window/dump notify.
  - PolicyEnforcer: generates seccomp profile (connect >=1024) with mode ERRNO/LOG, writes profile; suggests docker update (demo helper script).
  - AgentServer: receives `/long-dump-requests`, `/policies` (no auth, demo).
- Config env highlights:
  - `DK_SERVER_URL`, `DK_AGENT_ID`, `DK_CONTAINER_FILTER`, `DK_POLICY_ENFORCEMENT_MODE` (ERRNO|LOG), `DK_POLICY_DIR`, `DK_DOCKER_SOCKET`, etc.

## Server Components (implemented)
- Ingestion: `AgentWindowController` handles window POST → score/triage/policy → repos + WS events.
- Services: FeatureExtractor, ModelRegistryService (in-memory), ContainerViewService (aggregates verdict/score/policy), repos for windows/triage/policies/events.
- Ports/adapters: stub anomaly engine, stub triage, policy generator (default seccomp high-port block), agent control HTTP adapter (best-effort).
- WS: `/ws/events` STOMP/SockJS; REST: `/api/ui/containers`, `/models`, `/events`.

## UI (implemented)
- Dashboard (containers + KPIs), Container detail (status, sparkline, verdict, policy, models), Live Events feed, Model Explorer.
- Consumes REST + `/topic/events`.

## Demo App (Bachat Bank)
- Location: `demo-apps/bachat-bank/`
- Services: frontend (React) + backend (FastAPI) with `MODE=normal|malicious|safe`.
  - malicious: beacon to external high port + `/etc/passwd` exec.
  - safe: internal payments call.
- Compose file, mode switch script, README.

## Deployment (single-host demo)
1. Start server (`./gradlew :core:bootRun`) and UI (npm dev/build) on host; optional ML service.
2. Run agent with root/BPF, filter `bachat` compose namespace.
3. Bring up demo app via `docker-compose` in `demo-apps/bachat-bank/`.
4. Switch backend mode (malicious/safe) to drive THREAT/SAFE flows; apply seccomp profile with `scripts/apply-seccomp-profile.sh` if demonstrating enforcement.

## Known Limitations (demo state)
- Anomaly, triage, and model training are stubbed; enforcement is best-effort (seccomp profile written, docker update manual).
- Agent HTTP server lacks auth/TLS (demo only).
- Docker mapping uses heuristics and may fallback on non-standard setups.
- Seccomp connect>=1024 filter may still block unexpected traffic; use LOG mode if needed.

