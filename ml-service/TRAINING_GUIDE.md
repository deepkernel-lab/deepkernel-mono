# DeepKernel ML Model Training Guide

## For LLM Agent Execution

This guide contains step-by-step commands to train the Isolation Forest model for the Bachat Bank demo application. Execute each step in order, verify the expected output, and troubleshoot if needed.

---

## Prerequisites

- Python 3.10+ installed
- Working directory: `deepkernel-mono` repository root
- Windows PowerShell or Linux bash

---

## STEP 1: Navigate to ML Service Directory

```bash
cd ml-service
```

**Verify:** You should see these files:
```
conftest.py  Dockerfile  README.md  requirements.txt  src/  tests/  tools/
```

**Command to verify:**
```bash
ls -la
# or on Windows:
dir
```

---

## STEP 2: Create and Activate Virtual Environment

### On Windows (PowerShell):
```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
```

### On Linux/Mac:
```bash
python3 -m venv .venv
source .venv/bin/activate
```

**Verify:** Your prompt should show `(.venv)` prefix.

**If activation fails on Windows** (execution policy):
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
.\.venv\Scripts\Activate.ps1
```

---

## STEP 3: Install Dependencies

```bash
pip install -r requirements.txt
```

**Expected output:** Should end with:
```
Successfully installed fastapi-0.115.0 numpy-1.26.0 scikit-learn-1.3.2 ...
```

**Verify installation:**
```bash
pip list | grep -E "fastapi|scikit-learn|numpy"
```

**Expected:**
```
fastapi         0.115.0
numpy           1.26.0
scikit-learn    1.3.2
```

**If pip install fails:**
```bash
pip install --upgrade pip
pip install -r requirements.txt
```

---

## STEP 4: Create Models Directory

```bash
mkdir -p models
```

**On Windows:**
```powershell
New-Item -ItemType Directory -Force -Path models
```

---

## STEP 5: Start the ML Service

Open a **new terminal** (keep this running):

```bash
cd ml-service
.\.venv\Scripts\Activate.ps1  # Windows
# or: source .venv/bin/activate  # Linux

python -m src.main
```

**Expected output:**
```
2025-12-12 10:00:00 - src.main - INFO - Starting DeepKernel ML Service...
2025-12-12 10:00:00 - src.main - INFO - ML Service started on port 8081
INFO:     Uvicorn running on http://0.0.0.0:8081 (Press CTRL+C to quit)
```

**Verify service is running** (in another terminal):
```bash
curl http://localhost:8081/health
```

**Expected response:**
```json
{"status":"ok","message":"ML service is running","model_count":0,"version":"0.1.0"}
```

**On Windows without curl:**
```powershell
Invoke-RestMethod -Uri http://localhost:8081/health
```

**If port 8081 is in use:**
```bash
ML_SERVICE_PORT=8082 python -m src.main
```

---

## STEP 6: Train Model Using Synthetic Baseline

In a **separate terminal** (with venv activated):

```bash
cd ml-service
.\.venv\Scripts\Activate.ps1  # Activate venv

# Train model for bachat-backend container
python tools/train_baseline.py --container-id bachat-backend --samples 100
```

**Expected output:**
```
🔧 Generating 100 synthetic baseline feature vectors...
   Simulating normal web application behavior
   Generated 100 × 594-dim vectors

🔗 Connecting to ML service: http://localhost:8081
   ML service is healthy

🎯 Training model for container: bachat-backend

✅ Training complete!
   Model ID:      model-bachat-backend
   Version:       1
   Status:        READY
   Sample count:  100
   Trained at:    2025-12-12T10:05:00.123456+00:00
```

**If you get "ML service is not reachable":**
1. Check ML service is running in the other terminal
2. Verify with `curl http://localhost:8081/health`
3. If using different port, add: `--ml-service-url http://localhost:8082`

---

## STEP 7: Train Model for Frontend Container

```bash
python tools/train_baseline.py --container-id bachat-frontend --samples 100
```

**Expected output:** Similar to Step 6, with `bachat-frontend` container.

---

## STEP 8: Verify Trained Models

### List all models:
```bash
curl http://localhost:8081/api/ml/models
```

**Expected response:**
```json
[
  {
    "model_id": "model-bachat-backend",
    "container_id": "bachat-backend",
    "version": 1,
    "feature_version": "v1",
    "status": "READY",
    "trained_at": "2025-12-12T10:05:00.123456+00:00",
    "sample_count": 100,
    "parameters": {
      "n_estimators": 100,
      "contamination": 0.1,
      "max_samples": 100,
      "random_state": 42
    }
  },
  {
    "model_id": "model-bachat-frontend",
    "container_id": "bachat-frontend",
    ...
  }
]
```

### Get specific model:
```bash
curl http://localhost:8081/api/ml/models/bachat-backend
```

---

## STEP 9: Test Anomaly Scoring

### Test normal behavior (should NOT be anomalous):
```bash
curl -X POST http://localhost:8081/api/ml/score \
  -H "Content-Type: application/json" \
  -d "{\"container_id\": \"bachat-backend\", \"feature_vector\": $(python -c "print([0.01]*594)")}"
```

**On Windows PowerShell:**
```powershell
$vector = (0.01,)*594 -join ','
$body = @{
    container_id = "bachat-backend"
    feature_vector = @(0.01) * 594
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Uri http://localhost:8081/api/ml/score -Method POST -Body $body -ContentType "application/json"
```

**Expected response:**
```json
{
  "score": -0.15,
  "anomalous": false,
  "container_id": "bachat-backend",
  "model_version": 1
}
```

- `score` > -0.5: Normal behavior
- `score` < -0.5: Anomalous behavior
- `anomalous`: true if score < threshold

---

## STEP 10: Verify Model Files on Disk

```bash
ls -la models/
```

**Expected output:**
```
bachat-backend.pkl
bachat-frontend.pkl
```

**On Windows:**
```powershell
Get-ChildItem models
```

---

## WHERE ARE THE TRAINED MODEL FILES?

| Location | Description |
|----------|-------------|
| **Default path** | `ml-service/models/` |
| **File format** | `<container_id>.pkl` (Python pickle) |
| **Example files** | `models/bachat-backend.pkl`, `models/bachat-frontend.pkl` |
| **Custom path** | Set `MODEL_STORAGE_PATH` env var |

**Full paths:**
```
deepkernel-mono/
└── ml-service/
    └── models/
        ├── bachat-backend.pkl    # ~50KB per model
        └── bachat-frontend.pkl
```

**To use custom storage:**
```bash
MODEL_STORAGE_PATH=/var/deepkernel/models python -m src.main
```

---

## STEP 11: Run Unit Tests (Optional)

```bash
cd ml-service
pytest -v
```

**Expected output:**
```
tests/test_health.py::test_health PASSED
tests/test_health.py::test_api_health PASSED
tests/test_score.py::test_score_untrained_model PASSED
tests/test_score.py::test_score_after_training PASSED
tests/test_train.py::test_train_model_success PASSED
...
=============== X passed in Y.YYs ===============
```

---

## TROUBLESHOOTING

### Issue: "ModuleNotFoundError: No module named 'src'"
**Fix:** Run from ml-service directory with venv activated:
```bash
cd ml-service
source .venv/bin/activate  # or .\.venv\Scripts\Activate.ps1
python -m src.main
```

### Issue: "Connection refused" when training
**Fix:** Ensure ML service is running:
```bash
curl http://localhost:8081/health
# If fails, start the service in another terminal
```

### Issue: "Training requires at least 10 samples"
**Fix:** Increase samples:
```bash
python tools/train_baseline.py --container-id bachat-backend --samples 50
```

### Issue: "Feature vector must have exactly 594 dimensions"
**Fix:** Ensure your feature vector has exactly 594 floats.

### Issue: Model file not created
**Fix:** Check models directory exists and is writable:
```bash
mkdir -p models
chmod 755 models  # Linux only
```

### Issue: Port already in use
**Fix:** Use different port:
```bash
ML_SERVICE_PORT=8082 python -m src.main
python tools/train_baseline.py --ml-service-url http://localhost:8082 -c bachat-backend
```

---

## COMPLETE COMMAND SEQUENCE (Copy-Paste Ready)

### Terminal 1 - Start ML Service:
```bash
cd ml-service
python -m venv .venv
# Windows: .\.venv\Scripts\Activate.ps1
# Linux: source .venv/bin/activate
pip install -r requirements.txt
mkdir -p models
python -m src.main
```

### Terminal 2 - Train Models:
```bash
cd ml-service
# Windows: .\.venv\Scripts\Activate.ps1
# Linux: source .venv/bin/activate

# Train both containers
python tools/train_baseline.py --container-id bachat-backend --samples 100
python tools/train_baseline.py --container-id bachat-frontend --samples 100

# Verify
curl http://localhost:8081/api/ml/models
ls models/
```

---

## SUCCESS CRITERIA

✅ ML service running on port 8081  
✅ Health endpoint returns `{"status":"ok"}`  
✅ Two models trained: `bachat-backend`, `bachat-frontend`  
✅ Models list shows `"status": "READY"` for both  
✅ Model files exist: `models/bachat-backend.pkl`, `models/bachat-frontend.pkl`  
✅ Scoring endpoint returns valid scores  

