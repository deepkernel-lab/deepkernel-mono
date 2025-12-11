"""Tests for scoring endpoint."""
import pytest
from fastapi.testclient import TestClient

from src.main import app
from src.config import config


def test_score_untrained_model(client):
    """Test scoring with untrained model returns neutral score."""
    response = client.post("/api/ml/score", json={
        "container_id": "test-container",
        "feature_vector": [0.1] * config.feature_vector_dim
    })
    assert response.status_code == 200
    data = response.json()
    assert "score" in data
    assert "anomalous" in data
    assert data["container_id"] == "test-container"
    # Untrained model should return neutral score
    assert data["score"] == 0.0
    assert data["anomalous"] is False


def test_score_validates_feature_vector_dimension(client):
    """Test that wrong feature vector dimension is rejected."""
    response = client.post("/api/ml/score", json={
        "container_id": "test-container",
        "feature_vector": [0.1] * 100  # Wrong dimension
    })
    assert response.status_code == 422


def test_score_after_training(client):
    """Test scoring after training returns valid score."""
    container_id = "test-score-after-train"
    
    # First, train a model
    training_data = [[0.1] * config.feature_vector_dim for _ in range(50)]
    train_response = client.post("/api/ml/train", json={
        "container_id": container_id,
        "training_data": training_data,
    })
    assert train_response.status_code == 200
    
    # Now score with similar data (should be normal)
    score_response = client.post("/api/ml/score", json={
        "container_id": container_id,
        "feature_vector": [0.1] * config.feature_vector_dim
    })
    assert score_response.status_code == 200
    data = score_response.json()
    assert isinstance(data["score"], float)
    assert isinstance(data["anomalous"], bool)
    assert data["model_version"] == 1


def test_score_anomalous_pattern(client):
    """Test that anomalous patterns are detected."""
    container_id = "test-anomaly-detection"
    
    # Train with uniform low values
    training_data = [[0.01] * config.feature_vector_dim for _ in range(100)]
    client.post("/api/ml/train", json={
        "container_id": container_id,
        "training_data": training_data,
    })
    
    # Score with very different pattern
    anomalous_vector = [10.0] * config.feature_vector_dim
    response = client.post("/api/ml/score", json={
        "container_id": container_id,
        "feature_vector": anomalous_vector
    })
    assert response.status_code == 200
    data = response.json()
    # The score should be lower (more negative) for anomalous patterns
    assert data["score"] < 0

