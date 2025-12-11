"""Pytest configuration and fixtures."""
import pytest
from starlette.testclient import TestClient
import os
import shutil
from src.main import app
from src.config import config


@pytest.fixture(scope="session")
def client():
    """
    Test client for the FastAPI application.
    This fixture will be used by all tests.
    It ensures that startup and shutdown events are run, and that the
    model storage is cleaned up before and after the test session.
    """
    storage_path = config.model_storage_path

    # Clean up before tests
    if os.path.exists(storage_path):
        shutil.rmtree(storage_path)
    os.makedirs(storage_path, exist_ok=True)

    with TestClient(app) as c:
        yield c

    # Clean up after tests
    if os.path.exists(storage_path):
        shutil.rmtree(storage_path)