"""
Isolation Forest model wrapper for anomaly detection.
"""
import logging
from datetime import datetime, timezone
from typing import List, Optional, Tuple

import numpy as np
from sklearn.ensemble import IsolationForest

from ..config import config

logger = logging.getLogger(__name__)


class IsolationForestModel:
    """
    Wrapper around scikit-learn's Isolation Forest for per-container anomaly detection.
    
    The Isolation Forest algorithm isolates anomalies by randomly selecting a feature
    and then randomly selecting a split value between the maximum and minimum values
    of the selected feature. Anomalies are easier to isolate (require fewer splits)
    than normal points.
    """
    
    def __init__(
        self,
        container_id: str,
        n_estimators: int = None,
        contamination: float = None,
        max_samples: int = None,
        random_state: int = None,
    ):
        """
        Initialize an Isolation Forest model for a container.
        
        Args:
            container_id: Unique identifier for the container
            n_estimators: Number of trees in the forest
            contamination: Expected proportion of anomalies
            max_samples: Number of samples to draw for each tree
            random_state: Random seed for reproducibility
        """
        self.container_id = container_id
        self.n_estimators = n_estimators or config.n_estimators
        self.contamination = contamination or config.contamination
        self.max_samples = max_samples or config.max_samples
        self.random_state = random_state or config.random_state
        
        self.model: Optional[IsolationForest] = None
        self.version: int = 0
        self.trained_at: Optional[datetime] = None
        self.sample_count: int = 0
        self.is_fitted: bool = False
        
        logger.info(
            f"Initialized IsolationForestModel for {container_id} "
            f"(n_estimators={self.n_estimators}, contamination={self.contamination})"
        )
    
    @property
    def model_id(self) -> str:
        """Generate model ID from container ID."""
        return f"model-{self.container_id.replace('/', '-')}"
    
    @property
    def parameters(self) -> dict:
        """Get model parameters."""
        return {
            "n_estimators": self.n_estimators,
            "contamination": self.contamination,
            "max_samples": self.max_samples,
            "random_state": self.random_state,
        }
    
    def train(self, training_data: List[List[float]]) -> Tuple[int, datetime]:
        """
        Train the Isolation Forest model on the provided data.
        
        Args:
            training_data: List of feature vectors (each 594 dimensions)
            
        Returns:
            Tuple of (version, trained_at timestamp)
        """
        if len(training_data) < 10:
            raise ValueError(f"Need at least 10 samples for training, got {len(training_data)}")
        
        # Convert to numpy array
        X = np.array(training_data, dtype=np.float32)
        
        # Handle NaN/Inf values
        X = np.nan_to_num(X, nan=0.0, posinf=1e6, neginf=-1e6)
        
        logger.info(f"Training model for {self.container_id} with {len(X)} samples")
        
        # Create and fit the model
        self.model = IsolationForest(
            n_estimators=self.n_estimators,
            contamination=self.contamination,
            max_samples=min(self.max_samples, len(X)),
            random_state=self.random_state,
            n_jobs=-1,  # Use all CPU cores
            warm_start=False,
        )
        
        self.model.fit(X)
        
        # Update metadata
        self.version += 1
        self.trained_at = datetime.now(timezone.utc)
        self.sample_count = len(X)
        self.is_fitted = True
        
        logger.info(
            f"Model trained for {self.container_id}: version={self.version}, "
            f"samples={self.sample_count}"
        )
        
        return self.version, self.trained_at
    
    def score(self, feature_vector: List[float]) -> Tuple[float, bool]:
        """
        Score a single feature vector for anomaly detection.
        
        Args:
            feature_vector: 594-dimensional feature vector
            
        Returns:
            Tuple of (anomaly_score, is_anomalous)
            - anomaly_score: Negative values indicate anomalies
            - is_anomalous: True if score is below threshold
        """
        if not self.is_fitted or self.model is None:
            # Return neutral score for untrained model
            logger.warning(f"Model for {self.container_id} is not trained, returning neutral score")
            return 0.0, False
        
        # Convert to numpy array and reshape for single sample
        X = np.array([feature_vector], dtype=np.float32)
        X = np.nan_to_num(X, nan=0.0, posinf=1e6, neginf=-1e6)
        
        # Get anomaly score
        # score_samples returns the anomaly score for each sample
        # Negative scores indicate anomalies
        score = float(self.model.score_samples(X)[0])
        
        # Determine if anomalous based on threshold
        is_anomalous = score < config.anomaly_threshold
        
        logger.debug(
            f"Scored for {self.container_id}: score={score:.4f}, "
            f"anomalous={is_anomalous}, threshold={config.anomaly_threshold}"
        )
        
        return score, is_anomalous
    
    def predict(self, feature_vector: List[float]) -> int:
        """
        Predict if a feature vector is an anomaly.
        
        Args:
            feature_vector: 594-dimensional feature vector
            
        Returns:
            1 for normal, -1 for anomaly
        """
        if not self.is_fitted or self.model is None:
            return 1  # Assume normal for untrained model
        
        X = np.array([feature_vector], dtype=np.float32)
        X = np.nan_to_num(X, nan=0.0, posinf=1e6, neginf=-1e6)
        
        return int(self.model.predict(X)[0])

