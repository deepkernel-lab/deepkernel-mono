"""
Pydantic schemas for ML Service request/response models.
"""
from datetime import datetime
from enum import Enum
from typing import List, Optional, Dict, Any

from pydantic import BaseModel, Field, field_validator

from .config import config


class ModelStatus(str, Enum):
    """Model training status."""
    UNTRAINED = "UNTRAINED"
    TRAINING = "TRAINING"
    READY = "READY"
    ERROR = "ERROR"


# ============== Request Schemas ==============

class ScoreRequest(BaseModel):
    """Request to score a feature vector for anomaly detection."""
    container_id: str = Field(..., description="Container identifier")
    feature_vector: List[float] = Field(..., description="594-dimensional feature vector")
    
    @field_validator("feature_vector")
    @classmethod
    def validate_feature_vector_length(cls, v: List[float]) -> List[float]:
        if len(v) != config.feature_vector_dim:
            raise ValueError(
                f"Feature vector must have exactly {config.feature_vector_dim} dimensions, "
                f"got {len(v)}"
            )
        return v


class TrainingContext(BaseModel):
    """Context for model training."""
    reason: Optional[str] = Field(None, description="Reason for training")
    min_records_per_window: int = Field(20, description="Minimum records per window")


class TrainRequest(BaseModel):
    """Request to train an Isolation Forest model."""
    container_id: str = Field(..., description="Container identifier")
    training_data: List[List[float]] = Field(
        ..., 
        description="List of feature vectors for training",
        min_length=1
    )
    context: Optional[TrainingContext] = Field(None, description="Training context")
    
    @field_validator("training_data")
    @classmethod
    def validate_training_data(cls, v: List[List[float]]) -> List[List[float]]:
        if len(v) < 10:
            raise ValueError("Training requires at least 10 samples")
        for i, fv in enumerate(v):
            if len(fv) != config.feature_vector_dim:
                raise ValueError(
                    f"Feature vector at index {i} has {len(fv)} dimensions, "
                    f"expected {config.feature_vector_dim}"
                )
        return v


# ============== Response Schemas ==============

class ScoreResponse(BaseModel):
    """Response from anomaly scoring."""
    score: float = Field(..., description="Anomaly score (negative = more anomalous)")
    anomalous: bool = Field(..., description="Whether the window is considered anomalous")
    container_id: str = Field(..., description="Container identifier")
    model_version: Optional[int] = Field(None, description="Model version used for scoring")


class TrainResponse(BaseModel):
    """Response from model training."""
    model_id: str = Field(..., description="Model identifier")
    container_id: str = Field(..., description="Container identifier")
    version: int = Field(..., description="Model version number")
    status: ModelStatus = Field(..., description="Model status")
    trained_at: datetime = Field(..., description="Training timestamp")
    sample_count: int = Field(..., description="Number of training samples")


class ModelMeta(BaseModel):
    """Model metadata."""
    model_id: str = Field(..., description="Model identifier")
    container_id: str = Field(..., description="Container identifier")
    version: int = Field(..., description="Model version number")
    feature_version: str = Field("v1", description="Feature vector version")
    status: ModelStatus = Field(..., description="Model status")
    trained_at: Optional[datetime] = Field(None, description="Training timestamp")
    sample_count: Optional[int] = Field(None, description="Number of training samples")
    parameters: Optional[Dict[str, Any]] = Field(None, description="Model parameters")


class HealthResponse(BaseModel):
    """Health check response."""
    status: str = Field(..., description="Service status")
    message: str = Field(..., description="Status message")
    model_count: int = Field(..., description="Number of loaded models")
    version: str = Field(..., description="Service version")


class ErrorResponse(BaseModel):
    """Error response."""
    error: str = Field(..., description="Error type")
    message: str = Field(..., description="Error message")
    detail: Optional[str] = Field(None, description="Additional details")

