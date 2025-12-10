# DeepKernel Monorepo

DeepKernel is an intelligent runtime security platform that uses eBPF agents to capture per-container syscall flows, scores them with per-container Isolation Forest models, triages with LLM context, and enforces policies (Seccomp/AppArmor/NetworkPolicy). This monorepo contains the agent, server, optional ML service, contracts, and frontend.

## Repository Structure
```
deepkernel/
├─ MASTER_PLAN.md                # Source-of-truth architecture
├─ FRONDEND_PLAN.md              # Frontend design
├─ agent/                        # C++17 eBPF agent
│  ├─ src/                       # user-space runtime
│  ├─ include/                   # headers (event types, config, serialization)
│  ├─ ebpf/                      # deepkernel.bpf.c
│  ├─ config/                    # agent-config.yaml sample
│  └─ CMakeLists.txt
├─ server/                       # Spring Boot backend (multi-module Gradle)
│  ├─ core/                      # APIs, orchestration, websocket
│  ├─ anomaly-engine/            # Isolation Forest adapter (stubbed)
│  ├─ triage-service/            # LLM triage adapter (stubbed)
│  ├─ cicd-integration/          # change-context adapter (stubbed)
│  ├─ policy-engine/             # policy generation
│  ├─ contracts/                 # shared DTOs
│  └─ ui-frontend/               # React + Tailwind SPA
├─ ml-service/                   # Optional FastAPI service (stub)
├─ contracts/                    # lang-agnostic contracts placeholder
├─ docs/                         # FEATURES/API stubs
├─ scripts/                      # helper scripts (mock-agent-send, smoke)
└─ docker-compose.demo.yml       # demo scaffold (server/ml/mock-agent)
```

## End-to-End Flow (demo path)
1. Agent captures syscalls via eBPF, batches 5s windows, and POSTs `/api/v1/agent/windows` to the server.
2. Server extracts features, scores via anomaly engine, triages (stub), generates policy (stub), stores state, and publishes WebSocket events (`WINDOW_SCORED`, `TRIAGE_RESULT`, `POLICY_APPLIED`).
3. UI consumes REST (`/api/ui/containers`, `/models`, `/events`) and WS (`/ws/events`) to render dashboard, container detail, live feed, and model explorer.
4. Optional ML service can be called if remote mode is enabled (stubbed for now).

## Building Components

### Prerequisites (Ubuntu)
- System: `build-essential`, `git`, `pkg-config`
- Agent/BPF: CMake ≥3.16, `clang`, `llvm`, `libbpf-dev`, `libelf-dev`, `zlib1g-dev`, `libcurl4-openssl-dev`
- Server: JDK 17, Gradle wrapper (bundled)
- UI: Node.js 18+, npm
- ML service: Python 3.10+, pip
- Optional: Docker / docker-compose (for demo)

### Agent (C++)
```bash
sudo apt-get update
sudo apt-get install -y clang llvm libbpf-dev libelf-dev zlib1g-dev libcurl4-openssl-dev cmake build-essential pkg-config
cmake -S agent -B build
cmake --build build
```
Artifacts: `build/deepkernel-agent`, `build/deepkernel.bpf.o`.

### Server (Java, multi-module)
```bash
cd server
./gradlew build
```
Starts (after build) with `./gradlew :core:bootRun`.

### Frontend (React/Vite)
```bash
cd server/ui-frontend
npm install
npm run build
```
Dev server: `npm run dev` (default 5173).

### ML Service (FastAPI)
```bash
cd ml-service
pip install -r requirements.txt
uvicorn src.main:app --reload --port 8081
```

## Running / Deploying Components (single-host demo)
1) Start server:
```bash
cd server
./gradlew :core:bootRun
# Optional env:
#   DEEPKERNEL_AGENT_BASE_URL=http://localhost:8080
#   DEEPKERNEL_WS_ALLOWED_ORIGINS=http://localhost:5173
#   DEEPKERNEL_UI_ORIGIN=http://localhost:5173
```
2) Start ML service (optional):
```bash
cd ml-service
pip install -r requirements.txt
uvicorn src.main:app --host 0.0.0.0 --port 8081
```
3) Start UI:
```bash
cd server/ui-frontend
npm install
npm run dev   # or npm run build && npm run preview
```
4) Run agent (needs root/BPF):
```bash
cd agent/build
sudo ./deepkernel-agent   # ensure deepkernel.bpf.o is beside the binary
```
5) Smoke test:
```bash
./scripts/mock-agent-send.sh   # send sample window
./scripts/smoke.sh             # send + fetch containers/events
```
6) Docker compose demo (optional):
```bash
docker-compose -f docker-compose.demo.yml up --build
```

## Running Tests

### Unit Tests
- Agent:
  ```bash
  cmake -S agent -B build && cmake --build build
  CTEST_OUTPUT_ON_FAILURE=1 ctest --test-dir build
  ```
- Server:
  ```bash
  cd server
  ./gradlew test
  ```
- UI:
  ```bash
  cd server/ui-frontend
  npm install
  npm test
  ```
- ML service:
  ```bash
  cd ml-service
  pip install -r requirements.txt
  pytest -q
  ```

### Integration / Smoke
- Post a sample window to a running server:
  ```bash
  ./scripts/mock-agent-send.sh
  ```
- Smoke flow (send + fetch containers/events):
  ```bash
  ./scripts/smoke.sh
  ```
- Optional compose scaffold:
  ```bash
  docker-compose -f docker-compose.demo.yml up --build
  ```

## Key REST & WS Endpoints (server)
- Agent ingest: `POST /api/v1/agent/windows`
- UI data: `GET /api/ui/containers`, `GET /api/ui/containers/{id}/models`, `GET /api/ui/events`
- WebSocket/SockJS: `/ws/events` (subscribe `/topic/events`)

## Configuration (demo defaults)
- Agent: `agent/config/agent-config.yaml` (edit `serverUrl`, container allowlist).
- Server props (env): `DEEPKERNEL_AGENT_BASE_URL`, `DEEPKERNEL_WS_ALLOWED_ORIGINS`, `DEEPKERNEL_UI_ORIGIN`.

## Notes
- Anomaly, triage, and policy logic are stubbed for demo; replace with real implementations as needed.
- Container mapping currently uses cgroup IDs; integrate with real metadata for production.