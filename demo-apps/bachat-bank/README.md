# Bachat Bank Demo

Two-service banking demo used to showcase DeepKernel anomaly detection, triage, and policy enforcement with a malicious change (THREAT) and a safe change (SAFE + continuous learning).

## Services
- `backend` (FastAPI, Python): static auth/account data. MODE env toggles behavior:
  - `normal`: baseline only.
  - `malicious`: beacon to high-port URL (EXFIL_URL, default https://example.com:4444/steal) + `/etc/passwd` shell read on account request.
  - `safe`: calls internal payments URL (PAYMENTS_URL, default http://payments-internal:8080/reconcile) on account access.
- `frontend` (React, served with `serve`): login form (stub), account summary, shows backend mode.

## Run (compose)
```bash
cd demo-apps/bachat-bank
docker-compose up -d --build          # MODE=normal by default
docker-compose logs -f
```

Frontend: http://localhost:3000  
Backend: internal at http://backend:8000 (inside compose)

Switch modes:
```bash
MODE=malicious ./scripts/set-mode.sh   # THREAT scenario
MODE=safe ./scripts/set-mode.sh        # SAFE scenario
MODE=normal ./scripts/set-mode.sh      # baseline
```

Environment:
- MODE (normal|malicious|safe)
- EXFIL_URL (only malicious)
- PAYMENTS_URL (only safe)
- VITE_BACKEND_URL (frontend, defaults to http://backend:8000)

## DeepKernel integration (demo)
- Run DeepKernel agent + server on host; set agent to monitor compose network `bachat` / containers `frontend` and `backend`.
- Malicious mode triggers outbound connect to high port and optional `/etc/passwd` exec → expect THREAT, seccomp/netpolicy demo.
- Safe mode triggers outbound to LAN:8080 with diff summary → expect SAFE + retrain.

## App endpoints
- Backend: `GET /health`, `POST /login` (returns token), `GET /account` (Authorization: Bearer token).
- Frontend: static SPA; fetches backend directly.

## Build locally (optional)
```bash
# backend
cd backend
pip install -r requirements.txt
uvicorn main:app --reload --port 8000

# frontend
cd frontend
npm install
npm run dev
```

