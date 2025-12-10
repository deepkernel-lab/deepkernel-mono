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

## API Endpoints

### Health Check

```
GET /health
GET /api/health
```

Returns service status.

### Score Window

```
POST /api/ml/score
```

Score a feature vector for anomaly detection.

**Request:**
```json
{
  "container_id": "prod/billing-api",
  "feature_vector": [0.1, 0.2, ..., 0.0]  // 594 dimensions
}
```

**Response:**
```json
{
  "score": -0.45,
  "anomalous": false,
  "container_id": "prod/billing-api",
  "model_version": 1
}
```

### Train Model

```
POST /api/ml/train
```

Train an Isolation Forest model for a container.

**Request:**
```json
{
  "container_id": "prod/billing-api",
  "training_data": [[...], [...], ...],
  "context": {
    "reason": "baseline_training",
    "min_records_per_window": 20
  }
}
```

**Response:**
```json
{
  "model_id": "model-prod-billing-api",
  "container_id": "prod/billing-api",
  "version": 1,
  "status": "READY",
  "trained_at": "2025-12-10T20:00:00Z",
  "sample_count": 1500
}
```

### Get Model Metadata

```
GET /api/ml/models/{container_id}
```

Get metadata for a container's model.

**Response:**
```json
{
  "model_id": "model-prod-billing-api",
  "container_id": "prod/billing-api",
  "version": 2,
  "feature_version": "v1",
  "status": "READY",
  "trained_at": "2025-12-10T20:00:00Z",
  "sample_count": 1500,
  "parameters": {
    "n_estimators": 100,
    "contamination": 0.1
  }
}
```

### List Models

```
GET /api/ml/models
```

List all registered models.

### Delete Model

```
DELETE /api/ml/models/{container_id}
```

Delete a container's model.

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

