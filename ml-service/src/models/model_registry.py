"""
Model registry for managing per-container Isolation Forest models.
"""
import logging
import os
import pickle
import threading
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from ..config import config
from ..schemas import ModelMeta, ModelStatus
from .isolation_forest import IsolationForestModel

logger = logging.getLogger(__name__)


class ModelRegistry:
    """
    Thread-safe registry for managing per-container Isolation Forest models.
    
    Supports:
    - In-memory model storage
    - Optional persistence to disk
    - Model versioning
    - Thread-safe operations
    """
    
    def __init__(self, storage_path: Optional[str] = None):
        """
        Initialize the model registry.
        
        Args:
            storage_path: Path for model persistence (None for in-memory only)
        """
        self._models: Dict[str, IsolationForestModel] = {}
        self._lock = threading.RLock()
        self._storage_path = storage_path or config.model_storage_path
        
        # Create storage directory if persistence is enabled
        if self._storage_path:
            Path(self._storage_path).mkdir(parents=True, exist_ok=True)
            logger.info(f"Model registry initialized with storage at {self._storage_path}")
        else:
            logger.info("Model registry initialized (in-memory only)")
    
    def _get_model_path(self, container_id: str) -> Path:
        """Get the file path for a container's model."""
        safe_id = container_id.replace("/", "_").replace("\\", "_")
        return Path(self._storage_path) / f"{safe_id}.pkl"
    
    def get_or_create_model(self, container_id: str) -> IsolationForestModel:
        """
        Get an existing model or create a new one for a container.
        
        Args:
            container_id: Container identifier
            
        Returns:
            IsolationForestModel instance
        """
        with self._lock:
            if container_id not in self._models:
                # Try to load from disk first
                model = self._load_from_disk(container_id)
                if model is None:
                    model = IsolationForestModel(container_id)
                self._models[container_id] = model
                logger.info(f"Created/loaded model for {container_id}")
            
            return self._models[container_id]
    
    def get_model(self, container_id: str) -> Optional[IsolationForestModel]:
        """
        Get an existing model for a container.
        
        Args:
            container_id: Container identifier
            
        Returns:
            IsolationForestModel instance or None if not found
        """
        with self._lock:
            if container_id in self._models:
                return self._models[container_id]
            
            # Try to load from disk
            model = self._load_from_disk(container_id)
            if model:
                self._models[container_id] = model
                return model
            
            return None
    
    def train_model(
        self,
        container_id: str,
        training_data: List[List[float]],
        persist: bool = True
    ) -> Tuple[int, datetime]:
        """
        Train a model for a container.
        
        Args:
            container_id: Container identifier
            training_data: List of feature vectors
            persist: Whether to persist the model to disk
            
        Returns:
            Tuple of (version, trained_at timestamp)
        """
        model = self.get_or_create_model(container_id)
        
        with self._lock:
            version, trained_at = model.train(training_data)
            
            if persist and self._storage_path:
                self._save_to_disk(container_id, model)
            
            return version, trained_at
    
    def score(self, container_id: str, feature_vector: List[float]) -> Tuple[float, bool, Optional[int]]:
        """
        Score a feature vector using the container's model.
        
        Args:
            container_id: Container identifier
            feature_vector: 594-dimensional feature vector
            
        Returns:
            Tuple of (score, is_anomalous, model_version)
        """
        model = self.get_model(container_id)
        
        if model is None:
            # No model for this container - return neutral score
            logger.warning(f"No model found for {container_id}, returning neutral score")
            return 0.0, False, None
        
        score, is_anomalous = model.score(feature_vector)
        return score, is_anomalous, model.version if model.is_fitted else None
    
    def get_model_meta(self, container_id: str) -> Optional[ModelMeta]:
        """
        Get metadata for a container's model.
        
        Args:
            container_id: Container identifier
            
        Returns:
            ModelMeta or None if no model exists
        """
        model = self.get_model(container_id)
        
        if model is None:
            return None
        
        status = ModelStatus.READY if model.is_fitted else ModelStatus.UNTRAINED
        
        return ModelMeta(
            model_id=model.model_id,
            container_id=model.container_id,
            version=model.version,
            feature_version="v1",
            status=status,
            trained_at=model.trained_at,
            sample_count=model.sample_count if model.is_fitted else None,
            parameters=model.parameters if model.is_fitted else None,
        )
    
    def list_models(self) -> List[ModelMeta]:
        """
        List all registered models.
        
        Returns:
            List of ModelMeta for all models
        """
        with self._lock:
            result = []
            for container_id in self._models:
                meta = self.get_model_meta(container_id)
                if meta:
                    result.append(meta)
            return result
    
    def delete_model(self, container_id: str) -> bool:
        """
        Delete a container's model.
        
        Args:
            container_id: Container identifier
            
        Returns:
            True if model was deleted, False if not found
        """
        with self._lock:
            if container_id in self._models:
                del self._models[container_id]
                
                # Delete from disk if exists
                model_path = self._get_model_path(container_id)
                if model_path.exists():
                    model_path.unlink()
                    logger.info(f"Deleted persisted model for {container_id}")
                
                logger.info(f"Deleted model for {container_id}")
                return True
            
            return False
    
    @property
    def model_count(self) -> int:
        """Get the number of registered models."""
        with self._lock:
            return len(self._models)
    
    def _save_to_disk(self, container_id: str, model: IsolationForestModel) -> None:
        """Save a model to disk."""
        try:
            model_path = self._get_model_path(container_id)
            with open(model_path, "wb") as f:
                pickle.dump(model, f)
            logger.info(f"Persisted model for {container_id} to {model_path}")
        except Exception as e:
            logger.error(f"Failed to persist model for {container_id}: {e}")
    
    def _load_from_disk(self, container_id: str) -> Optional[IsolationForestModel]:
        """Load a model from disk."""
        model_path = self._get_model_path(container_id)
        
        if not model_path.exists():
            return None
        
        try:
            with open(model_path, "rb") as f:
                model = pickle.load(f)
            logger.info(f"Loaded model for {container_id} from {model_path}")
            return model
        except Exception as e:
            logger.error(f"Failed to load model for {container_id}: {e}")
            return None


# Global registry instance
registry = ModelRegistry()

