"""Tests for model metadata endpoint."""
import pytest
from fastapi.testclient import TestClient

from src.main import app
from src.config import config


def test_get_model_meta_untrained(client):
    """Test getting metadata for untrained model."""
    response = client.get("/api/ml/models/unknown-container")
    
    assert response.status_code == 200
    data = response.json()
    assert data["container_id"] == "unknown-container"
    assert data["status"] == "UNTRAINED"
    assert data["version"] == 0


def test_get_model_meta_after_training(client):
    """Test getting metadata after training."""
    container_id = "test-meta-trained"
    training_data = [[0.1] * config.feature_vector_dim for _ in range(50)]
    
    # Train model
    client.post("/api/ml/train", json={
        "container_id": container_id,
        "training_data": training_data,
    })
    
    # Get metadata
    response = client.get(f"/api/ml/models/{container_id}")
    
    assert response.status_code == 200
    data = response.json()
    assert data["container_id"] == container_id
    assert data["status"] == "READY"
    assert data["version"] == 1
    assert data["sample_count"] == 50
    assert "parameters" in data
    assert data["parameters"]["n_estimators"] == config.n_estimators


def test_list_models(client):
    """Test listing all models."""
    response = client.get("/api/ml/models")
    
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)


def test_delete_model(client):
    """Test deleting a model."""
    container_id = "test-delete-model"
    training_data = [[0.1] * config.feature_vector_dim for _ in range(50)]
    
    # Train model
    client.post("/api/ml/train", json={
        "container_id": container_id,
        "training_data": training_data,
    })
    
    # Delete model
    delete_response = client.delete(f"/api/ml/models/{container_id}")
    assert delete_response.status_code == 200
    
    # Verify deleted
    meta_response = client.get(f"/api/ml/models/{container_id}")
    assert meta_response.json()["status"] == "UNTRAINED"


def test_delete_nonexistent_model(client):
    """Test deleting a non-existent model returns 404."""
    response = client.delete("/api/ml/models/nonexistent-container")
    assert response.status_code == 404

