"""
Configuration module for DeepKernel ML Service.
Loads settings from environment variables with sensible defaults.
"""
import os
from dataclasses import dataclass


@dataclass
class Config:
    """ML Service configuration."""
    
    # Server settings
    host: str = "0.0.0.0"
    port: int = 8081
    log_level: str = "INFO"
    
    # Model storage
    model_storage_path: str = "./models"
    
    # Feature vector settings
    feature_vector_dim: int = 594
    
    # Isolation Forest hyperparameters
    n_estimators: int = 100
    contamination: float = 0.1
    max_samples: int = 256
    random_state: int = 42
    
    # Anomaly threshold (scores below this are flagged as anomalous)
    # More negative = more strict (fewer false positives, may miss some anomalies)
    # Less negative = more sensitive (catches more anomalies, more false positives)
    # -0.65 is good for demos (more sensitive to anomalies)
    anomaly_threshold: float = -0.65
    
    @classmethod
    def from_env(cls) -> "Config":
        """Load configuration from environment variables."""
        return cls(
            host=os.getenv("ML_SERVICE_HOST", "0.0.0.0"),
            port=int(os.getenv("ML_SERVICE_PORT", "8081")),
            log_level=os.getenv("LOG_LEVEL", "INFO"),
            model_storage_path=os.getenv("MODEL_STORAGE_PATH", "./models"),
            feature_vector_dim=int(os.getenv("FEATURE_VECTOR_DIM", "594")),
            n_estimators=int(os.getenv("ISOLATION_FOREST_N_ESTIMATORS", "100")),
            contamination=float(os.getenv("ISOLATION_FOREST_CONTAMINATION", "0.1")),
            max_samples=int(os.getenv("ISOLATION_FOREST_MAX_SAMPLES", "256")),
            random_state=int(os.getenv("ISOLATION_FOREST_RANDOM_STATE", "42")),
            anomaly_threshold=float(os.getenv("ANOMALY_THRESHOLD", "-0.65")),
        )


# Global config instance
config = Config.from_env()

