# deepkernel-mono

## Running tests (Ubuntu)

### Agent (C++)
```bash
cmake -S agent -B build && cmake --build build
CTEST_OUTPUT_ON_FAILURE=1 ctest --test-dir build
```

### Server (Java)
```bash
cd server
./gradlew test
```

### UI (React/Vite)
```bash
cd server/ui-frontend
npm install
npm test
```

### ML service (Python/FastAPI)
```bash
cd ml-service
pip install -r requirements.txt
pytest -q
```

### Integration smoke (optional)
```bash
./scripts/mock-agent-send.sh   # send sample window to running server
./scripts/smoke.sh             # send + fetch containers/events
```