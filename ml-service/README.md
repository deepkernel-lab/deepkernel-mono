# DeepKernel ML Service

Python-based Isolation Forest anomaly detection service for container syscall analysis.

## Overview

The ML service provides per-container Isolation Forest models for detecting anomalous syscall behavior patterns. It exposes a REST API that the Java server can call for scoring windows and training models.

## Features

- **Real Isolation Forest**: Uses scikit-learn's Isolation Forest algorithm
- **Per-Container Models**: Maintains separate models for each monitored container
- **Model Persistence**: Optionally persists trained models to disk
- **Thread-Safe**: Safe for concurrent requests
- **Configurable**: All parameters configurable via environment variables

## Prerequisites

- Python 3.10+
- pip

## Installation

```bash
cd ml-service

# Create virtual environment
python -m venv venv

# Activate virtual environment
# On Windows:
venv\Scripts\activate
# On Linux/Mac:
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

## Running

```bash
# Development (with auto-reload)
python -m src.main

# Or using uvicorn directly
uvicorn src.main:app --host 0.0.0.0 --port 8081 --reload

# Production
uvicorn src.main:app --host 0.0.0.0 --port 8081 --workers 4
```

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `ML_SERVICE_HOST` | `0.0.0.0` | Host to bind |
| `ML_SERVICE_PORT` | `8081` | Port to listen on |
| `LOG_LEVEL` | `INFO` | Logging level |
| `MODEL_STORAGE_PATH` | `./models` | Directory for model persistence |
| `FEATURE_VECTOR_DIM` | `594` | Expected feature vector dimensions |
| `ISOLATION_FOREST_N_ESTIMATORS` | `100` | Number of trees in forest |
| `ISOLATION_FOREST_CONTAMINATION` | `0.1` | Expected proportion of anomalies |
| `ISOLATION_FOREST_MAX_SAMPLES` | `256` | Samples per tree |
| `ANOMALY_THRESHOLD` | `-0.5` | Score threshold for anomaly detection |

## API Reference

### Health & Status

#### Health Check
```http
GET /health
GET /api/health
```

Returns service health status and model count.

**Response:**
```json
{
  "status": "ok",
  "message": "ML service is running",
  "model_count": 3,
  "version": "0.1.0"
}
```

---

### ML Operations

#### Score Window
```http
POST /api/ml/score
Content-Type: application/json
```

Score a feature vector for anomaly detection using the container's Isolation Forest model.

**Request:**
```json
{
  "container_id": "bachat-backend",
  "feature_vector": [0.1, 0.2, ..., 0.0]  // 594 dimensions
}
```

**Response:**
```json
{
  "score": -0.45,
  "anomalous": false,
  "container_id": "bachat-backend",
  "model_version": 1
}
```

**Status Codes:**
- `200 OK` - Successfully scored
- `422 Unprocessable Entity` - Invalid feature vector (wrong dimensions)
- `503 Service Unavailable` - Service not initialized

---

#### Train Model
```http
POST /api/ml/train
Content-Type: application/json
```

Train an Isolation Forest model for a specific container.

**Request:**
```json
{
  "container_id": "bachat-backend",
  "training_data": [
    [0.1, 0.2, ..., 0.0],  // Feature vector 1 (594 dims)
    [0.15, 0.18, ..., 0.01],  // Feature vector 2
    ...
  ],
  "context": {
    "reason": "baseline_training",
    "min_records_per_window": 20
  }
}
```

**Validation:**
- Minimum 10 training samples required
- Each feature vector must be exactly 594 dimensions

**Response:**
```json
{
  "model_id": "model-bachat-backend",
  "container_id": "bachat-backend",
  "version": 1,
  "status": "READY",
  "trained_at": "2025-12-10T20:00:00Z",
  "sample_count": 150
}
```

**Status Codes:**
- `200 OK` - Training successful
- `422 Unprocessable Entity` - Invalid training data
- `500 Internal Server Error` - Training failed

---

### Model Management

#### Get Model Metadata
```http
GET /api/ml/models/{container_id}
```

Get metadata for a container's model.

**Example:**
```bash
curl http://localhost:8081/api/ml/models/bachat-backend
```

**Response (Trained):**
```json
{
  "model_id": "model-bachat-backend",
  "container_id": "bachat-backend",
  "version": 2,
  "feature_version": "v1",
  "status": "READY",
  "trained_at": "2025-12-10T20:00:00Z",
  "sample_count": 150,
  "parameters": {
    "n_estimators": 100,
    "contamination": 0.1,
    "max_samples": 256,
    "random_state": 42
  }
}
```

**Response (Untrained):**
```json
{
  "model_id": "model-bachat-backend",
  "container_id": "bachat-backend",
  "version": 0,
  "feature_version": "v1",
  "status": "UNTRAINED",
  "trained_at": null,
  "sample_count": null,
  "parameters": null
}
```

---

#### List All Models
```http
GET /api/ml/models
```

List all registered models (trained and untrained).

**Response:**
```json
[
  {
    "model_id": "model-bachat-backend",
    "container_id": "bachat-backend",
    "version": 1,
    "status": "READY",
    ...
  },
  {
    "model_id": "model-bachat-frontend",
    "container_id": "bachat-frontend",
    "version": 2,
    "status": "READY",
    ...
  }
]
```

---

#### Delete Model
```http
DELETE /api/ml/models/{container_id}
```

Delete a container's model from memory and disk.

**Response:**
```json
{
  "status": "deleted",
  "container_id": "bachat-backend"
}
```

**Status Codes:**
- `200 OK` - Model deleted
- `404 Not Found` - No model found for container

---

## API Summary Table

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/health` | Service health check |
| `GET` | `/api/health` | Service health check (alias) |
| `POST` | `/api/ml/score` | Score a feature vector |
| `POST` | `/api/ml/train` | Train a model |
| `GET` | `/api/ml/models` | List all models |
| `GET` | `/api/ml/models/{container_id}` | Get model metadata |
| `DELETE` | `/api/ml/models/{container_id}` | Delete a model |

## Running Tests

```bash
# Run all tests
pytest

# Run with coverage
pytest --cov=src --cov-report=html

# Run specific test file
pytest tests/test_score.py -v
```

## Integration with Server

The Java server uses the `HybridAnomalyAdapter` which:

1. Tries to use the ML service first
2. Falls back to local `InProcessIsolationForestAdapter` if ML service is unavailable
3. Periodically checks if ML service becomes available

Configure the server with:

```yaml
deepkernel:
  anomaly:
    mode: HYBRID  # LOCAL, REMOTE, or HYBRID
  ml-service:
    url: http://localhost:8081
```

Or via environment variable:
```bash
ANOMALY_ENGINE_MODE=HYBRID
ML_SERVICE_URL=http://localhost:8081
```

## Docker

```bash
# Build image
docker build -t deepkernel-ml-service:latest .

# Run container
docker run -p 8081:8081 \
  -e LOG_LEVEL=INFO \
  -v ./models:/app/models \
  deepkernel-ml-service:latest
```

## Training Tools

The `tools/` directory contains utilities for training models:

### From Agent Binary Dump

When the agent collects a long dump (baseline recording), use:

```bash
python tools/train_from_dump.py /var/deepkernel/dumps/backend.bin \
    --container-id bachat-backend \
    --window-sec 5 \
    --min-records 20
```

Options:
- `--window-sec`: Window duration (default: 5 seconds)
- `--min-records`: Minimum syscalls per window (default: 20)
- `--dry-run`: Analyze dump without training
- `-v`: Verbose output

### Synthetic Baseline (Demo/Testing)

For quick demos without real syscall data:

```bash
python tools/train_baseline.py --container-id bachat-backend --samples 100
```

This generates synthetic feature vectors that mimic normal web application behavior.

## Architecture

```
ml-service/
├── src/
│   ├── main.py              # FastAPI app & routes
│   ├── config.py            # Configuration from env vars
│   ├── schemas.py           # Pydantic request/response models
│   └── models/
│       ├── isolation_forest.py  # IsolationForest wrapper
│       └── model_registry.py    # Per-container model storage
├── tools/
│   ├── train_from_dump.py   # Train from agent binary dumps
│   └── train_baseline.py    # Synthetic baseline training
├── tests/
│   ├── test_health.py
│   ├── test_score.py
│   ├── test_train.py
│   └── test_models.py
├── models/                  # Persisted model files
├── requirements.txt
├── Dockerfile
└── README.md
```


