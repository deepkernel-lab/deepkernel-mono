#!/usr/bin/env python3
"""
DeepKernel ML Pipeline - End-to-End Validation Script

Validates the complete ML training and scoring pipeline:
1. Check ML service health
2. Generate synthetic dump file (simulates agent output)
3. Parse and transform dump to feature vectors
4. Train Isolation Forest model
5. Score sample data and verify anomaly detection
6. Check model stats and metadata

Usage:
    python e2e_validate.py [--ml-service-url URL]

This script can be run without the agent or server running - it only needs ML service.
"""

import argparse
import os
import struct
import sys
import tempfile
import time
import random
from pathlib import Path
from typing import List, Tuple

try:
    import httpx
except ImportError:
    print("Error: httpx not installed. Run: pip install httpx")
    sys.exit(1)

# Import from sibling modules
sys.path.insert(0, str(Path(__file__).parent.parent))
from tools.train_from_dump import (
    parse_dump_file, 
    split_into_windows, 
    extract_features,
    TraceHeader,
    TraceRecord,
    HEADER_FORMAT,
    RECORD_FORMAT,
    FEATURE_DIM
)


# ==============================================================================
# Test Configuration
# ==============================================================================

TEST_CONTAINER_ID = "e2e-test-container"
TEST_DUMP_DURATION_SEC = 60  # 1 minute simulated dump
SYSCALLS_PER_SECOND = 100    # ~100 syscalls/sec for test
WINDOW_SIZE_SEC = 5
MIN_RECORDS_PER_WINDOW = 20


# ==============================================================================
# Synthetic Dump Generation
# ==============================================================================

def generate_normal_syscalls(count: int, seed: int = 42) -> List[Tuple[int, int, int]]:
    """
    Generate syscalls that represent NORMAL web app behavior.
    Returns list of (syscall_id, arg_class, arg_bucket)
    """
    random.seed(seed)
    
    # Normal web app syscall distribution
    normal_profile = [
        # (syscall_id, arg_class, weight) - common web app syscalls
        (0, 1, 30),    # read - FILE
        (1, 1, 25),    # write - FILE
        (257, 1, 15),  # openat - FILE
        (3, 1, 15),    # close - FILE
        (5, 1, 5),     # fstat - FILE
        (42, 2, 3),    # connect - NET
        (45, 2, 2),    # recvfrom - NET
        (44, 2, 2),    # sendto - NET
        (9, 4, 2),     # mmap - MEM
        (12, 4, 1),    # brk - MEM
    ]
    
    total_weight = sum(w for _, _, w in normal_profile)
    syscalls = []
    
    for _ in range(count):
        r = random.randint(0, total_weight - 1)
        cumulative = 0
        for syscall_id, arg_class, weight in normal_profile:
            cumulative += weight
            if r < cumulative:
                arg_bucket = random.randint(0, 3)
                syscalls.append((syscall_id, arg_class, arg_bucket))
                break
    
    return syscalls


def generate_anomalous_syscalls(count: int, seed: int = 999) -> List[Tuple[int, int, int]]:
    """
    Generate syscalls that represent ANOMALOUS behavior (attack pattern).
    Returns list of (syscall_id, arg_class, arg_bucket)
    """
    random.seed(seed)
    
    # Anomalous profile - suspicious syscalls
    anomalous_profile = [
        (59, 3, 20),   # execve - PROC (high frequency = suspicious)
        (101, 3, 15),  # ptrace - PROC (debugging/injection)
        (42, 2, 25),   # connect - NET (many connections)
        (87, 1, 10),   # unlink - FILE (file deletion)
        (90, 1, 10),   # chmod - FILE (permission changes)
        (165, 3, 10),  # mount - PROC (filesystem mount)
        (56, 3, 10),   # clone - PROC (process spawning)
    ]
    
    total_weight = sum(w for _, _, w in anomalous_profile)
    syscalls = []
    
    for _ in range(count):
        r = random.randint(0, total_weight - 1)
        cumulative = 0
        for syscall_id, arg_class, weight in anomalous_profile:
            cumulative += weight
            if r < cumulative:
                arg_bucket = random.randint(0, 3)
                syscalls.append((syscall_id, arg_class, arg_bucket))
                break
    
    return syscalls


def create_synthetic_dump(filepath: Path, container_id: str, duration_sec: int, 
                          syscalls_per_sec: int, anomalous: bool = False) -> int:
    """
    Create a synthetic binary dump file matching agent format.
    
    Returns:
        Number of records written
    """
    total_records = duration_sec * syscalls_per_sec
    
    if anomalous:
        syscalls = generate_anomalous_syscalls(total_records)
    else:
        syscalls = generate_normal_syscalls(total_records)
    
    with open(filepath, "wb") as f:
        # Write header
        header = struct.pack(
            HEADER_FORMAT,
            1,  # version
            256,  # syscall_vocab_size
            container_id.encode('utf-8').ljust(64, b'\x00'),
            int(time.time() * 1_000_000_000)  # start_ts_ns
        )
        f.write(header)
        
        # Write records
        avg_delta_us = 1_000_000 // syscalls_per_sec  # Microseconds between syscalls
        for syscall_id, arg_class, arg_bucket in syscalls:
            # Add some jitter to delta
            delta_us = avg_delta_us + random.randint(-avg_delta_us//2, avg_delta_us//2)
            delta_us = max(1, delta_us)
            
            record = struct.pack(RECORD_FORMAT, delta_us, syscall_id, arg_class, arg_bucket)
            f.write(record)
    
    return total_records


# ==============================================================================
# ML Service Client
# ==============================================================================

class MLServiceClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip('/')
        self.client = httpx.Client(timeout=60.0)
    
    def health(self) -> dict:
        """Check service health."""
        resp = self.client.get(f"{self.base_url}/health")
        resp.raise_for_status()
        return resp.json()
    
    def train(self, container_id: str, training_data: List[List[float]], reason: str = "e2e_test") -> dict:
        """Train a model."""
        resp = self.client.post(
            f"{self.base_url}/api/ml/train",
            json={
                "container_id": container_id,
                "training_data": training_data,
                "context": {"reason": reason}
            }
        )
        resp.raise_for_status()
        return resp.json()
    
    def score(self, container_id: str, feature_vector: List[float]) -> dict:
        """Score a feature vector."""
        resp = self.client.post(
            f"{self.base_url}/api/ml/score",
            json={
                "container_id": container_id,
                "feature_vector": feature_vector
            }
        )
        resp.raise_for_status()
        return resp.json()
    
    def get_model(self, container_id: str) -> dict:
        """Get model metadata."""
        resp = self.client.get(f"{self.base_url}/api/ml/models/{container_id}")
        resp.raise_for_status()
        return resp.json()
    
    def list_models(self) -> List[dict]:
        """List all models."""
        resp = self.client.get(f"{self.base_url}/api/ml/models")
        resp.raise_for_status()
        return resp.json()
    
    def delete_model(self, container_id: str) -> bool:
        """Delete a model."""
        resp = self.client.delete(f"{self.base_url}/api/ml/models/{container_id}")
        return resp.status_code == 200
    
    def close(self):
        self.client.close()


# ==============================================================================
# Validation Tests
# ==============================================================================

def test_health(client: MLServiceClient) -> bool:
    """Test 1: Health check."""
    print("\n" + "="*60)
    print("TEST 1: ML Service Health Check")
    print("="*60)
    
    try:
        result = client.health()
        print(f"  ✅ Status: {result.get('status')}")
        print(f"  ✅ Version: {result.get('version')}")
        print(f"  ✅ Model count: {result.get('model_count')}")
        return True
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False


def test_dump_generation(dump_path: Path) -> Tuple[bool, int]:
    """Test 2: Generate synthetic dump file."""
    print("\n" + "="*60)
    print("TEST 2: Synthetic Dump Generation")
    print("="*60)
    
    try:
        record_count = create_synthetic_dump(
            dump_path,
            TEST_CONTAINER_ID,
            TEST_DUMP_DURATION_SEC,
            SYSCALLS_PER_SECOND,
            anomalous=False
        )
        
        file_size = dump_path.stat().st_size
        expected_size = 80 + (record_count * 8)  # Header + records
        
        print(f"  ✅ Dump file created: {dump_path}")
        print(f"  ✅ File size: {file_size:,} bytes")
        print(f"  ✅ Records written: {record_count:,}")
        print(f"  ✅ Expected size: {expected_size:,} bytes")
        
        if file_size != expected_size:
            print(f"  ⚠️  Size mismatch (expected {expected_size})")
        
        return True, record_count
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False, 0


def test_dump_parsing(dump_path: Path, expected_records: int) -> Tuple[bool, List[TraceRecord]]:
    """Test 3: Parse binary dump file."""
    print("\n" + "="*60)
    print("TEST 3: Binary Dump Parsing")
    print("="*60)
    
    try:
        header, records = parse_dump_file(dump_path)
        
        print(f"  ✅ Header version: {header.version}")
        print(f"  ✅ Container ID: {header.container_id}")
        print(f"  ✅ Vocab size: {header.syscall_vocab_size}")
        print(f"  ✅ Records parsed: {len(records):,}")
        
        if len(records) != expected_records:
            print(f"  ⚠️  Expected {expected_records}, got {len(records)}")
        
        # Show sample records
        if records:
            print(f"  📋 Sample records:")
            for i, r in enumerate(records[:3]):
                print(f"      [{i}] syscall={r.syscall_id}, class={r.arg_class}, "
                      f"bucket={r.arg_bucket}, delta={r.delta_ts_us}µs")
        
        return True, records
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False, []


def test_feature_extraction(records: List[TraceRecord]) -> Tuple[bool, List[List[float]]]:
    """Test 4: Extract feature vectors from windows."""
    print("\n" + "="*60)
    print("TEST 4: Feature Extraction")
    print("="*60)
    
    try:
        windows = split_into_windows(records, WINDOW_SIZE_SEC * 1_000_000)
        print(f"  ✅ Windows created: {len(windows)}")
        
        valid_windows = [w for w in windows if len(w) >= MIN_RECORDS_PER_WINDOW]
        print(f"  ✅ Valid windows (≥{MIN_RECORDS_PER_WINDOW} records): {len(valid_windows)}")
        
        if len(valid_windows) < 10:
            print(f"  ❌ Not enough valid windows for training (need ≥10)")
            return False, []
        
        # Extract features
        training_data = []
        for window in valid_windows:
            fv = extract_features(window)
            training_data.append(fv)
        
        print(f"  ✅ Feature vectors extracted: {len(training_data)}")
        print(f"  ✅ Feature dimension: {len(training_data[0])}")
        
        if len(training_data[0]) != FEATURE_DIM:
            print(f"  ❌ Expected {FEATURE_DIM} dimensions, got {len(training_data[0])}")
            return False, []
        
        # Show feature stats
        all_vals = [v for fv in training_data for v in fv]
        print(f"  📊 Feature stats:")
        print(f"      min={min(all_vals):.4f}, max={max(all_vals):.4f}")
        print(f"      non-zero features: {sum(1 for v in all_vals if v != 0)}/{len(all_vals)}")
        
        return True, training_data
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False, []


def test_model_training(client: MLServiceClient, training_data: List[List[float]]) -> bool:
    """Test 5: Train Isolation Forest model."""
    print("\n" + "="*60)
    print("TEST 5: Model Training")
    print("="*60)
    
    try:
        # Delete any existing model first
        client.delete_model(TEST_CONTAINER_ID)
        
        result = client.train(TEST_CONTAINER_ID, training_data, "e2e_validation_test")
        
        print(f"  ✅ Model ID: {result.get('model_id')}")
        print(f"  ✅ Version: {result.get('version')}")
        print(f"  ✅ Status: {result.get('status')}")
        print(f"  ✅ Sample count: {result.get('sample_count')}")
        print(f"  ✅ Trained at: {result.get('trained_at')}")
        
        if result.get('status') != 'READY':
            print(f"  ❌ Expected status READY, got {result.get('status')}")
            return False
        
        return True
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False


def test_model_metadata(client: MLServiceClient) -> bool:
    """Test 6: Retrieve model metadata and stats."""
    print("\n" + "="*60)
    print("TEST 6: Model Metadata & Stats")
    print("="*60)
    
    try:
        meta = client.get_model(TEST_CONTAINER_ID)
        
        print(f"  ✅ Model ID: {meta.get('model_id')}")
        print(f"  ✅ Container: {meta.get('container_id')}")
        print(f"  ✅ Version: {meta.get('version')}")
        print(f"  ✅ Status: {meta.get('status')}")
        print(f"  ✅ Feature version: {meta.get('feature_version')}")
        print(f"  ✅ Sample count: {meta.get('sample_count')}")
        
        params = meta.get('parameters', {})
        if params:
            print(f"  📊 Model parameters:")
            print(f"      n_estimators: {params.get('n_estimators')}")
            print(f"      contamination: {params.get('contamination')}")
            print(f"      max_samples: {params.get('max_samples')}")
        
        return True
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False


def test_normal_scoring(client: MLServiceClient, training_data: List[List[float]]) -> Tuple[bool, float]:
    """Test 7: Score normal behavior (should NOT be anomalous)."""
    print("\n" + "="*60)
    print("TEST 7: Scoring Normal Behavior")
    print("="*60)
    
    try:
        # Use one of the training vectors (should be very normal)
        normal_vector = training_data[0]
        
        result = client.score(TEST_CONTAINER_ID, normal_vector)
        
        score = result.get('score', 0)
        anomalous = result.get('anomalous', True)
        
        print(f"  📊 Score: {score:.4f}")
        print(f"  📊 Anomalous: {anomalous}")
        print(f"  📊 Model version: {result.get('model_version')}")
        
        if anomalous:
            print(f"  ⚠️  Normal data flagged as anomalous (score={score:.4f})")
            print(f"      This might indicate model needs tuning")
        else:
            print(f"  ✅ Correctly identified as NORMAL")
        
        return not anomalous, score
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False, 0.0


def test_anomaly_scoring(client: MLServiceClient) -> Tuple[bool, float]:
    """Test 8: Score anomalous behavior (SHOULD be anomalous)."""
    print("\n" + "="*60)
    print("TEST 8: Scoring Anomalous Behavior")
    print("="*60)
    
    try:
        # Generate anomalous syscall pattern
        anomalous_syscalls = generate_anomalous_syscalls(500, seed=12345)
        
        # Convert to TraceRecord format
        records = []
        for syscall_id, arg_class, arg_bucket in anomalous_syscalls:
            records.append(TraceRecord(
                delta_ts_us=10000,  # 10ms between calls
                syscall_id=syscall_id,
                arg_class=arg_class,
                arg_bucket=arg_bucket
            ))
        
        # Extract features from anomalous data
        anomalous_vector = extract_features(records)
        
        result = client.score(TEST_CONTAINER_ID, anomalous_vector)
        
        score = result.get('score', 0)
        anomalous = result.get('anomalous', False)
        
        print(f"  📊 Score: {score:.4f}")
        print(f"  📊 Anomalous: {anomalous}")
        print(f"  📊 Model version: {result.get('model_version')}")
        
        if anomalous:
            print(f"  ✅ Correctly identified as ANOMALOUS")
        else:
            print(f"  ⚠️  Anomalous data NOT flagged (score={score:.4f})")
            print(f"      Model may need more diverse training data")
        
        return anomalous, score
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False, 0.0


def test_model_list(client: MLServiceClient) -> bool:
    """Test 9: List all models."""
    print("\n" + "="*60)
    print("TEST 9: Model Registry List")
    print("="*60)
    
    try:
        models = client.list_models()
        
        print(f"  ✅ Total models: {len(models)}")
        for m in models:
            status_emoji = "✅" if m.get('status') == 'READY' else "⏳"
            print(f"      {status_emoji} {m.get('container_id')}: v{m.get('version')} ({m.get('status')})")
        
        # Check our test model exists
        test_model = next((m for m in models if m.get('container_id') == TEST_CONTAINER_ID), None)
        if test_model:
            print(f"  ✅ Test model found in registry")
            return True
        else:
            print(f"  ❌ Test model NOT found in registry")
            return False
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False


def test_cleanup(client: MLServiceClient) -> bool:
    """Test 10: Cleanup - delete test model."""
    print("\n" + "="*60)
    print("TEST 10: Cleanup")
    print("="*60)
    
    try:
        deleted = client.delete_model(TEST_CONTAINER_ID)
        if deleted:
            print(f"  ✅ Test model deleted: {TEST_CONTAINER_ID}")
            return True
        else:
            print(f"  ⚠️  Model not found (may have been already deleted)")
            return True  # Not a failure
    except Exception as e:
        print(f"  ❌ FAILED: {e}")
        return False


# ==============================================================================
# Main
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="End-to-end validation of DeepKernel ML pipeline"
    )
    parser.add_argument("--ml-service-url", default="http://localhost:8081",
                        help="ML service URL")
    parser.add_argument("--keep-model", action="store_true",
                        help="Don't delete test model after validation")
    parser.add_argument("--keep-dump", action="store_true",
                        help="Don't delete synthetic dump file")
    
    args = parser.parse_args()
    
    print("="*60)
    print("DeepKernel ML Pipeline - End-to-End Validation")
    print("="*60)
    print(f"ML Service URL: {args.ml_service_url}")
    print(f"Test Container: {TEST_CONTAINER_ID}")
    
    # Create temp directory for dump file
    with tempfile.TemporaryDirectory() as tmpdir:
        dump_path = Path(tmpdir) / f"{TEST_CONTAINER_ID}.dkdump"
        
        client = MLServiceClient(args.ml_service_url)
        results = {}
        
        try:
            # Run tests
            results['health'] = test_health(client)
            if not results['health']:
                print("\n❌ ML service not available. Aborting.")
                sys.exit(1)
            
            results['dump_gen'], record_count = test_dump_generation(dump_path)
            results['dump_parse'], records = test_dump_parsing(dump_path, record_count)
            results['feature_extract'], training_data = test_feature_extraction(records)
            
            if training_data:
                results['training'] = test_model_training(client, training_data)
                results['metadata'] = test_model_metadata(client)
                results['normal_score'], normal_score = test_normal_scoring(client, training_data)
                results['anomaly_score'], anomaly_score = test_anomaly_scoring(client)
                results['model_list'] = test_model_list(client)
                
                if not args.keep_model:
                    results['cleanup'] = test_cleanup(client)
                else:
                    print("\n⚠️  Keeping test model (--keep-model flag)")
                    results['cleanup'] = True
            
            # Print summary
            print("\n" + "="*60)
            print("VALIDATION SUMMARY")
            print("="*60)
            
            passed = 0
            failed = 0
            for test_name, result in results.items():
                status = "✅ PASS" if result else "❌ FAIL"
                print(f"  {status}: {test_name}")
                if result:
                    passed += 1
                else:
                    failed += 1
            
            print(f"\n  Total: {passed} passed, {failed} failed")
            
            if failed == 0:
                print("\n🎉 All validation tests PASSED!")
                sys.exit(0)
            else:
                print(f"\n⚠️  {failed} test(s) failed")
                sys.exit(1)
                
        finally:
            client.close()
            
            if args.keep_dump and dump_path.exists():
                # Copy to current directory
                import shutil
                final_path = Path(f"./{TEST_CONTAINER_ID}.dkdump")
                shutil.copy(dump_path, final_path)
                print(f"\n📁 Dump file saved: {final_path}")


if __name__ == "__main__":
    main()

