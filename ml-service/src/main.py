"""
DeepKernel ML Service - FastAPI application for anomaly detection.

Provides REST API endpoints for:
- Anomaly scoring using Isolation Forest
- Model training per container
- Model metadata retrieval
"""
import logging
import sys
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Optional

from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import config
from .models import ModelRegistry
from .schemas import (
    ErrorResponse,
    HealthResponse,
    ModelMeta,
    ModelStats,
    ModelStatus,
    ScoreRequest,
    ScoreResponse,
    TrainRequest,
    TrainResponse,
)

# Configure logging
logging.basicConfig(
    level=getattr(logging, config.log_level.upper()),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger(__name__)

# Global model registry
registry: Optional[ModelRegistry] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler for startup/shutdown."""
    global registry
    
    # Startup
    logger.info("Starting DeepKernel ML Service...")
    registry = ModelRegistry(storage_path=config.model_storage_path)
    logger.info(f"ML Service started on port {config.port}")
    
    yield
    
    # Shutdown
    logger.info("Shutting down DeepKernel ML Service...")


# Create FastAPI application
app = FastAPI(
    title="DeepKernel ML Service",
    description="Isolation Forest anomaly detection service for container syscall analysis",
    version="0.1.0",
    lifespan=lifespan,
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============== Exception Handlers ==============

@app.exception_handler(ValueError)
async def value_error_handler(request, exc: ValueError):
    """Handle validation errors."""
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content=ErrorResponse(
            error="ValidationError",
            message=str(exc),
        ).model_dump(),
    )


@app.exception_handler(Exception)
async def general_exception_handler(request, exc: Exception):
    """Handle unexpected errors."""
    logger.exception(f"Unexpected error: {exc}")
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=ErrorResponse(
            error="InternalError",
            message="An unexpected error occurred",
            detail=str(exc) if config.log_level == "DEBUG" else None,
        ).model_dump(),
    )


# ============== Health Endpoints ==============

@app.get("/health", response_model=HealthResponse, tags=["Health"])
async def health():
    """
    Health check endpoint.
    
    Returns service status and basic statistics.
    """
    return HealthResponse(
        status="ok",
        message="ML service is running",
        model_count=registry.model_count if registry else 0,
        version="0.1.0",
    )


@app.get("/api/health", response_model=HealthResponse, tags=["Health"])
async def api_health():
    """Alias for health check at /api/health."""
    return await health()


# ============== Simulation State ==============

# Global simulation state for demo purposes
_simulation_state: dict = {
    "enabled": False,
    "containers": {},  # container_id -> {"score": float, "anomalous": bool}
}


# ============== ML Endpoints ==============

@app.post(
    "/api/ml/score",
    response_model=ScoreResponse,
    tags=["ML"],
    summary="Score a feature vector for anomaly detection",
)
async def score_window(request: ScoreRequest):
    """
    Score a feature vector using the container's Isolation Forest model.
    
    - **container_id**: Unique identifier for the container
    - **feature_vector**: 594-dimensional feature vector extracted from syscall window
    
    Returns an anomaly score where negative values indicate anomalies.
    
    NOTE: If simulation mode is enabled for this container, returns simulated score.
    """
    if registry is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service not initialized",
        )
    
    # Check if simulation is enabled for this container
    if _simulation_state["enabled"] and request.container_id in _simulation_state["containers"]:
        sim = _simulation_state["containers"][request.container_id]
        logger.info(
            f"[SIMULATION] Returning simulated score for {request.container_id}: "
            f"score={sim['score']:.4f}, anomalous={sim['anomalous']}"
        )
        return ScoreResponse(
            score=sim["score"],
            anomalous=sim["anomalous"],
            container_id=request.container_id,
            model_version=999,  # Indicate simulation
        )
    
    logger.debug(f"Scoring request for container {request.container_id}")
    
    score, is_anomalous, model_version = registry.score(
        container_id=request.container_id,
        feature_vector=request.feature_vector,
    )
    
    logger.info(
        f"Scored {request.container_id}: score={score:.4f}, anomalous={is_anomalous}"
    )
    
    return ScoreResponse(
        score=score,
        anomalous=is_anomalous,
        container_id=request.container_id,
        model_version=model_version,
    )


@app.post(
    "/api/ml/train",
    response_model=TrainResponse,
    tags=["ML"],
    summary="Train an Isolation Forest model for a container",
)
async def train_model(request: TrainRequest):
    """
    Train an Isolation Forest model for a specific container.
    
    - **container_id**: Unique identifier for the container
    - **training_data**: List of 594-dimensional feature vectors
    - **context**: Optional training context with reason and parameters
    
    The model will be trained on the provided data and stored for future scoring.
    """
    if registry is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service not initialized",
        )
    
    logger.info(
        f"Training request for container {request.container_id} "
        f"with {len(request.training_data)} samples"
    )
    
    try:
        version, trained_at = registry.train_model(
            container_id=request.container_id,
            training_data=request.training_data,
            persist=True,
        )
        
        model = registry.get_model(request.container_id)
        
        logger.info(
            f"Model trained for {request.container_id}: "
            f"version={version}, samples={len(request.training_data)}"
        )
        
        return TrainResponse(
            model_id=model.model_id if model else f"model-{request.container_id}",
            container_id=request.container_id,
            version=version,
            status=ModelStatus.READY,
            trained_at=trained_at,
            sample_count=len(request.training_data),
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=str(e),
        )
    except Exception as e:
        logger.exception(f"Training failed for {request.container_id}: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Training failed: {str(e)}",
        )


@app.get(
    "/api/ml/models/{container_id}",
    response_model=ModelMeta,
    tags=["ML"],
    summary="Get model metadata for a container",
)
async def get_model_meta(container_id: str):
    """
    Get metadata for a container's Isolation Forest model.
    
    - **container_id**: Unique identifier for the container
    
    Returns model metadata including version, status, and training information.
    """
    if registry is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service not initialized",
        )
    
    meta = registry.get_model_meta(container_id)
    
    if meta is None:
        # Return untrained model metadata
        return ModelMeta(
            model_id=f"model-{container_id.replace('/', '-')}",
            container_id=container_id,
            version=0,
            feature_version="v1",
            status=ModelStatus.UNTRAINED,
            trained_at=None,
            sample_count=None,
            parameters=None,
        )
    
    return meta


@app.get(
    "/api/ml/models",
    response_model=list[ModelMeta],
    tags=["ML"],
    summary="List all registered models",
)
async def list_models():
    """
    List all registered Isolation Forest models.
    
    Returns a list of model metadata for all containers with trained models.
    """
    if registry is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service not initialized",
        )
    
    return registry.list_models()


@app.delete(
    "/api/ml/models/{container_id}",
    tags=["ML"],
    summary="Delete a container's model",
)
async def delete_model(container_id: str):
    """
    Delete a container's Isolation Forest model.
    
    - **container_id**: Unique identifier for the container
    """
    if registry is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service not initialized",
        )
    
    deleted = registry.delete_model(container_id)
    
    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No model found for container {container_id}",
        )
    
    return {"status": "deleted", "container_id": container_id}


@app.get(
    "/api/ml/models/{container_id}/stats",
    response_model=ModelStats,
    tags=["ML"],
    summary="Get detailed model statistics",
)
async def get_model_stats(container_id: str):
    """
    Get detailed statistics for a container's Isolation Forest model.
    
    Includes:
    - Training parameters
    - Model internals (offset, feature importances)
    - Storage information
    """
    if registry is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Service not initialized",
        )
    
    stats = registry.get_model_stats(container_id)
    
    if stats is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No model found for container {container_id}",
        )
    
    return stats


# ============== Simulation Endpoints (Demo Only) ==============

@app.post(
    "/api/demo/simulate",
    tags=["Demo"],
    summary="Enable anomaly simulation for a container",
)
async def enable_simulation(
    container_id: str,
    score: float = -0.85,
    anomalous: bool = True,
):
    """
    Enable anomaly score simulation for demo purposes.
    
    When enabled, the /api/ml/score endpoint will return the simulated
    score instead of the real model score.
    
    - **container_id**: Container to simulate
    - **score**: Simulated anomaly score (default: -0.85 = highly anomalous)
    - **anomalous**: Whether to flag as anomalous (default: True)
    
    Example: /api/demo/simulate?container_id=bachat-bank_backend_1&score=-0.9&anomalous=true
    """
    _simulation_state["enabled"] = True
    _simulation_state["containers"][container_id] = {
        "score": score,
        "anomalous": anomalous,
    }
    
    logger.warning(
        f"[SIMULATION ENABLED] {container_id}: score={score}, anomalous={anomalous}"
    )
    
    return {
        "status": "simulation_enabled",
        "container_id": container_id,
        "simulated_score": score,
        "simulated_anomalous": anomalous,
        "message": f"All scoring requests for {container_id} will now return simulated values",
    }


@app.post(
    "/api/demo/simulate/disable",
    tags=["Demo"],
    summary="Disable anomaly simulation",
)
async def disable_simulation(container_id: str = None):
    """
    Disable anomaly score simulation.
    
    - **container_id**: Specific container to disable (None = disable all)
    """
    if container_id:
        if container_id in _simulation_state["containers"]:
            del _simulation_state["containers"][container_id]
            logger.info(f"[SIMULATION DISABLED] {container_id}")
        if not _simulation_state["containers"]:
            _simulation_state["enabled"] = False
    else:
        _simulation_state["enabled"] = False
        _simulation_state["containers"] = {}
        logger.info("[SIMULATION DISABLED] All containers")
    
    return {
        "status": "simulation_disabled",
        "container_id": container_id or "all",
        "active_simulations": list(_simulation_state["containers"].keys()),
    }


@app.get(
    "/api/demo/simulate/status",
    tags=["Demo"],
    summary="Get simulation status",
)
async def get_simulation_status():
    """Get current simulation status for all containers."""
    return {
        "enabled": _simulation_state["enabled"],
        "containers": _simulation_state["containers"],
    }


# ============== Main Entry Point ==============

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "src.main:app",
        host=config.host,
        port=config.port,
        reload=True,
        log_level=config.log_level.lower(),
    )
