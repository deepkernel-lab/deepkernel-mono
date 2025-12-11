"""Tests for training endpoint."""
import pytest
from fastapi.testclient import TestClient

from src.main import app
from src.config import config


def test_train_model_success(client):
    """Test successful model training."""
    training_data = [[0.1] * config.feature_vector_dim for _ in range(50)]
    
    response = client.post("/api/ml/train", json={
        "container_id": "test-train-success",
        "training_data": training_data,
    })
    
    assert response.status_code == 200
    data = response.json()
    assert data["container_id"] == "test-train-success"
    assert data["version"] == 1
    assert data["status"] == "READY"
    assert data["sample_count"] == 50
    assert "trained_at" in data
    assert "model_id" in data


def test_train_with_context(client):
    """Test training with context."""
    training_data = [[0.1] * config.feature_vector_dim for _ in range(50)]
    
    response = client.post("/api/ml/train", json={
        "container_id": "test-train-context",
        "training_data": training_data,
        "context": {
            "reason": "baseline_training",
            "min_records_per_window": 20
        }
    })
    
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "READY"


def test_train_insufficient_data(client):
    """Test training with insufficient data fails."""
    training_data = [[0.1] * config.feature_vector_dim for _ in range(5)]  # Too few
    
    response = client.post("/api/ml/train", json={
        "container_id": "test-insufficient",
        "training_data": training_data,
    })
    
    assert response.status_code == 422


def test_train_wrong_dimension(client):
    """Test training with wrong feature dimension fails."""
    training_data = [[0.1] * 100 for _ in range(50)]  # Wrong dimension
    
    response = client.post("/api/ml/train", json={
        "container_id": "test-wrong-dim",
        "training_data": training_data,
    })
    
    assert response.status_code == 422


def test_train_increments_version(client):
    """Test that retraining increments version."""
    container_id = "test-version-increment"
    training_data = [[0.1] * config.feature_vector_dim for _ in range(50)]
    
    # First training
    response1 = client.post("/api/ml/train", json={
        "container_id": container_id,
        "training_data": training_data,
    })
    assert response1.json()["version"] == 1
    
    # Second training
    response2 = client.post("/api/ml/train", json={
        "container_id": container_id,
        "training_data": training_data,
    })
    assert response2.json()["version"] == 2

