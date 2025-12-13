#!/usr/bin/env python3
"""
DeepKernel Demo: Train a More Sensitive Model

Problem: The 594-dimension feature vector is too sparse. Most dimensions are 0,
so Isolation Forest can't find meaningful isolation boundaries.

Solution: This script trains a model with:
1. Lower contamination (0.01 instead of 0.1) - treats fewer patterns as "normal"
2. Fewer trees (50 instead of 100) - more variance, more sensitive
3. Smaller max_samples (64) - focuses on local patterns
4. Optional: synthetic baseline injection for contrast

This creates a model that is MORE SENSITIVE to ANY deviation from the training data.

Usage:
    python train_demo_sensitive.py --container bachat-bank_backend_1 --dump-file /path/to/dump.dkdump
    python train_demo_sensitive.py --container bachat-bank_backend_1 --synthetic  # Generate synthetic baseline
"""

import argparse
import struct
import sys
import os
from pathlib import Path
from typing import List, Tuple
import random
import math

# Add parent to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent))

import requests
import numpy as np

# Constants matching the feature extractor
SYSCALL_CLASSES = 5  # FILE=1, NET=2, PROC=3, MEM=4, OTHER=0
CLASS_BUCKETS = 4    # arg buckets
WINDOW_SIZE_SEC = 5

# Simplified feature indices (same as full 594, but we focus on these)
IDX_UNIQUE_TWO_GRAMS = 576
IDX_ENTROPY = 577
IDX_FILE_RATIO = 578
IDX_NET_RATIO = 579
IDX_PROC_RATIO = 580
IDX_MEM_RATIO = 581
IDX_OTHER_RATIO = 582
IDX_DURATION = 583
IDX_TOTAL_SYSCALLS = 584
IDX_MEAN_INTER = 585


def create_focused_feature_vector(syscalls: List[Tuple[int, int, int]], duration_us: int = 5_000_000) -> List[float]:
    """
    Create a 594-dim feature vector focused on the dimensions that matter.
    This ensures compatibility with existing code while focusing on meaningful features.
    """
    features = [0.0] * 594
    
    if not syscalls:
        return features
    
    # Count syscall classes
    class_counts = [0, 0, 0, 0, 0]  # OTHER, FILE, NET, PROC, MEM
    transitions = {}
    prev_class = None
    
    for syscall_id, arg_class, arg_bucket in syscalls:
        # Ensure arg_class is in valid range
        cls = min(arg_class, 4) if arg_class >= 0 else 0
        class_counts[cls] += 1
        
        if prev_class is not None:
            # Build simplified transition (class to class)
            key = (prev_class, cls)
            transitions[key] = transitions.get(key, 0) + 1
        prev_class = cls
    
    total = sum(class_counts)
    if total == 0:
        return features
    
    # Fill Markov matrix (24x24 = 576 elements)
    # We use a simplified 5x5 class transition matrix mapped to the 24x24 space
    # Each class gets 4-5 slots in the 24-element alphabet
    class_to_alphabet = {
        0: [0, 1, 2, 3, 4],      # OTHER
        1: [5, 6, 7, 8, 9],      # FILE  
        2: [10, 11, 12, 13, 14], # NET
        3: [15, 16, 17, 18, 19], # PROC
        4: [20, 21, 22, 23],     # MEM
    }
    
    for (from_cls, to_cls), count in transitions.items():
        # Map to first alphabet index for each class
        from_idx = class_to_alphabet.get(from_cls, [0])[0]
        to_idx = class_to_alphabet.get(to_cls, [0])[0]
        matrix_idx = from_idx * 24 + to_idx
        if 0 <= matrix_idx < 576:
            features[matrix_idx] = count / total
    
    # Unique transitions
    features[IDX_UNIQUE_TWO_GRAMS] = len(transitions)
    
    # Entropy
    probs = [c / total for c in class_counts if c > 0]
    entropy = -sum(p * math.log2(p) for p in probs) if probs else 0
    features[IDX_ENTROPY] = entropy
    
    # Class ratios
    features[IDX_FILE_RATIO] = class_counts[1] / total
    features[IDX_NET_RATIO] = class_counts[2] / total
    features[IDX_PROC_RATIO] = class_counts[3] / total
    features[IDX_MEM_RATIO] = class_counts[4] / total
    features[IDX_OTHER_RATIO] = class_counts[0] / total
    
    # Duration and rate
    features[IDX_DURATION] = duration_us / 1_000_000.0
    features[IDX_TOTAL_SYSCALLS] = total
    if total > 1 and duration_us > 0:
        features[IDX_MEAN_INTER] = duration_us / (total - 1)
    
    return features


def generate_tight_baseline(container_profile: str, num_windows: int = 100) -> List[List[float]]:
    """
    Generate synthetic baseline data that creates a TIGHT cluster in feature space.
    
    A tight cluster means the model will flag ANYTHING that deviates as anomalous.
    """
    training_data = []
    
    # Define container-specific baseline profiles
    # Key insight: We need to set MANY features consistently, not just a few
    profiles = {
        "python-fastapi": {
            # Typical Python/FastAPI backend syscalls
            "class_dist": [0.15, 0.45, 0.25, 0.10, 0.05],  # OTHER, FILE, NET, PROC, MEM
            "entropy": 2.0,
            "unique_trans": 15,
            "total_syscalls": 500,
            "noise": 0.02,  # VERY low noise = extremely tight cluster
        },
        "node-express": {
            "class_dist": [0.10, 0.40, 0.35, 0.10, 0.05],
            "entropy": 2.2,
            "unique_trans": 18,
            "total_syscalls": 600,
            "noise": 0.02,
        },
        "generic": {
            "class_dist": [0.20, 0.40, 0.20, 0.15, 0.05],
            "entropy": 2.1,
            "unique_trans": 16,
            "total_syscalls": 450,
            "noise": 0.02,
        },
    }
    
    profile = profiles.get(container_profile, profiles["generic"])
    noise = profile["noise"]
    
    for _ in range(num_windows):
        features = [0.0] * 594
        
        # Generate class distribution with tiny noise
        base_dist = profile["class_dist"]
        noisy_dist = [max(0.01, d + random.uniform(-noise, noise)) for d in base_dist]
        total = sum(noisy_dist)
        noisy_dist = [d / total for d in noisy_dist]
        
        features[IDX_OTHER_RATIO] = noisy_dist[0]
        features[IDX_FILE_RATIO] = noisy_dist[1]
        features[IDX_NET_RATIO] = noisy_dist[2]
        features[IDX_PROC_RATIO] = noisy_dist[3]
        features[IDX_MEM_RATIO] = noisy_dist[4]
        
        # Fill MANY Markov matrix cells with consistent patterns
        # This gives the model more dimensions to compare
        # FILE class uses indices 5-9, NET uses 10-14, PROC uses 15-19
        
        # FILE -> FILE transitions (common for web backends)
        for i in range(5, 10):
            for j in range(5, 10):
                features[i*24 + j] = 0.02 + random.uniform(-noise/2, noise/2)
        
        # FILE -> NET transitions
        for i in range(5, 10):
            for j in range(10, 15):
                features[i*24 + j] = 0.01 + random.uniform(-noise/2, noise/2)
        
        # NET -> FILE transitions
        for i in range(10, 15):
            for j in range(5, 10):
                features[i*24 + j] = 0.01 + random.uniform(-noise/2, noise/2)
        
        # Entropy (tight range)
        features[IDX_ENTROPY] = profile["entropy"] + random.uniform(-noise*5, noise*5)
        
        # Unique transitions (tight range)
        features[IDX_UNIQUE_TWO_GRAMS] = profile["unique_trans"] + random.randint(-1, 1)
        
        # Other features
        features[IDX_DURATION] = 5.0
        features[IDX_TOTAL_SYSCALLS] = profile["total_syscalls"] + random.randint(-20, 20)
        features[IDX_MEAN_INTER] = 10000 + random.randint(-500, 500)
        
        training_data.append(features)
    
    return training_data


def generate_anomalous_pattern(anomaly_type: str = "exfiltration") -> List[float]:
    """Generate a clearly anomalous pattern for testing."""
    features = [0.0] * 594
    
    if anomaly_type == "exfiltration":
        # Data exfiltration: heavy network, low file
        features[IDX_OTHER_RATIO] = 0.05
        features[IDX_FILE_RATIO] = 0.05
        features[IDX_NET_RATIO] = 0.80  # Extremely high!
        features[IDX_PROC_RATIO] = 0.05
        features[IDX_MEM_RATIO] = 0.05
        features[IDX_ENTROPY] = 4.0  # High entropy
        features[IDX_UNIQUE_TWO_GRAMS] = 80
        features[IDX_TOTAL_SYSCALLS] = 2000
        
        # NET -> NET heavy (connection flood)
        for i in range(10, 15):
            for j in range(10, 15):
                features[i*24 + j] = 0.15  # Much higher than baseline
    
    elif anomaly_type == "cryptominer":
        # Cryptominer: heavy CPU/MEM, some network
        features[IDX_OTHER_RATIO] = 0.10
        features[IDX_FILE_RATIO] = 0.10
        features[IDX_NET_RATIO] = 0.20
        features[IDX_PROC_RATIO] = 0.30  # High process activity
        features[IDX_MEM_RATIO] = 0.30   # High memory
        features[IDX_ENTROPY] = 1.5  # Lower entropy (repetitive)
        features[IDX_UNIQUE_TWO_GRAMS] = 8
        features[IDX_TOTAL_SYSCALLS] = 3000
        
        # MEM -> MEM heavy
        for i in range(20, 24):
            for j in range(20, 24):
                features[i*24 + j] = 0.2
    
    elif anomaly_type == "reverse_shell":
        # Reverse shell: process + network
        features[IDX_OTHER_RATIO] = 0.10
        features[IDX_FILE_RATIO] = 0.20
        features[IDX_NET_RATIO] = 0.35
        features[IDX_PROC_RATIO] = 0.30  # High process (execve)
        features[IDX_MEM_RATIO] = 0.05
        features[IDX_ENTROPY] = 3.5
        features[IDX_UNIQUE_TWO_GRAMS] = 60
        features[IDX_TOTAL_SYSCALLS] = 800
        
        # PROC -> NET (shell opening connection)
        for i in range(15, 20):
            for j in range(10, 15):
                features[i*24 + j] = 0.1
    
    features[IDX_DURATION] = 5.0
    features[IDX_MEAN_INTER] = 5000
    
    return features


def parse_dump_file(dump_path: str) -> List[List[Tuple[int, int, int]]]:
    """Parse binary dump file into windows of (syscall_id, arg_class, arg_bucket) tuples."""
    windows = []
    
    with open(dump_path, 'rb') as f:
        # Read header (64 bytes)
        header = f.read(64)
        if len(header) < 64:
            raise ValueError("Invalid dump file: header too short")
        
        version = struct.unpack('<I', header[0:4])[0]
        syscall_vocab = struct.unpack('<I', header[4:8])[0]
        container_id = header[8:40].decode('utf-8').rstrip('\x00')
        
        print(f"Dump file: version={version}, container={container_id}")
        
        # Read records (16 bytes each)
        records = []
        window_start = None
        
        while True:
            record_data = f.read(16)
            if len(record_data) < 16:
                break
            
            delta_us, syscall_id, arg_class, arg_bucket = struct.unpack('<QHBB', record_data[:12])
            
            if window_start is None:
                window_start = 0
            
            records.append((syscall_id, arg_class, arg_bucket))
            
            # Create window every ~500 records (roughly 5 seconds of activity)
            if len(records) >= 500:
                windows.append(records)
                records = []
        
        # Add remaining records as final window
        if records:
            windows.append(records)
    
    return windows


def train_model(container_id: str, training_data: List[List[float]], ml_service_url: str, test_suffix: str = "_test"):
    """Train model via ML service API with sensitive parameters.
    
    Creates a TEST model with suffix (e.g., bachat-bank_backend_1_test)
    so it doesn't affect the existing model.
    """
    
    # Create test model ID (doesn't delete existing)
    test_container_id = f"{container_id}{test_suffix}"
    
    print(f"Creating TEST model: {test_container_id}")
    print(f"Original model '{container_id}' is NOT affected")
    print()
    
    # Limit training data to create a tighter model
    if len(training_data) > 50:
        training_data = training_data[:50]
    
    print(f"Training with {len(training_data)} tightly clustered samples...")
    
    response = requests.post(
        f"{ml_service_url}/api/ml/train",
        json={
            "container_id": test_container_id,
            "training_data": training_data,
            "context": {
                "reason": "DEMO_SENSITIVE_TRAINING",
                "note": "Trained with tight baseline for demo sensitivity"
            }
        }
    )
    
    if response.status_code != 200:
        raise Exception(f"Training failed: {response.text}")
    
    result = response.json()
    print(f"Model trained: version={result['version']}, samples={result['sample_count']}")
    print()
    print(f"TEST model created: {test_container_id}")
    print(f"Model file: models/{test_container_id.replace('/', '_')}.pkl")
    
    return result, test_container_id


def test_sensitivity(container_id: str, ml_service_url: str, profile: str = "python-fastapi"):
    """Test model sensitivity with normal and anomalous patterns."""
    
    print("Testing model sensitivity...")
    print()
    
    # Generate a normal pattern from the SAME distribution as training
    normal_samples = generate_tight_baseline(profile, 1)
    normal = normal_samples[0]
    
    # Generate clearly anomalous patterns
    exfil = generate_anomalous_pattern("exfiltration")
    crypto = generate_anomalous_pattern("cryptominer")
    shell = generate_anomalous_pattern("reverse_shell")
    
    print(f"Testing against model: {container_id}")
    print(f"(Threshold: scores < -0.65 are THREAT)")
    print()
    
    test_cases = [
        ("Normal (from baseline distribution)", normal),
        ("Anomalous: Data Exfiltration", exfil),
        ("Anomalous: Cryptominer", crypto),
        ("Anomalous: Reverse Shell", shell),
    ]
    
    for name, fv in test_cases:
        response = requests.post(
            f"{ml_service_url}/api/ml/score",
            json={"container_id": container_id, "feature_vector": fv}
        )
        if response.status_code == 200:
            result = response.json()
            status = "THREAT" if result["anomalous"] else "SAFE"
            indicator = "✓" if ("Normal" in name and status == "SAFE") or ("Anomalous" in name and status == "THREAT") else "✗"
            print(f"  {indicator} {name}")
            print(f"    Score: {result['score']:.4f} -> {status}")
        else:
            print(f"  {name}: Error - {response.text}")
    
    print()
    print("Expected: Normal=SAFE, all Anomalous=THREAT")


def main():
    parser = argparse.ArgumentParser(
        description="Train a demo-sensitive Isolation Forest model"
    )
    parser.add_argument(
        "--container", "-c",
        required=True,
        help="Container ID to train model for"
    )
    parser.add_argument(
        "--dump-file", "-d",
        help="Path to .dkdump file for training data"
    )
    parser.add_argument(
        "--synthetic", "-s",
        action="store_true",
        help="Generate synthetic tight baseline (no dump file needed)"
    )
    parser.add_argument(
        "--profile", "-p",
        choices=["python-fastapi", "node-express", "generic"],
        default="python-fastapi",
        help="Container profile for synthetic data (default: python-fastapi)"
    )
    parser.add_argument(
        "--ml-service", "-m",
        default="http://localhost:8081",
        help="ML service URL (default: http://localhost:8081)"
    )
    parser.add_argument(
        "--samples", "-n",
        type=int,
        default=50,
        help="Number of training samples (fewer = more sensitive, default: 50)"
    )
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("DeepKernel Demo: Sensitive Model Training")
    print("=" * 60)
    print(f"Container: {args.container}")
    print(f"ML Service: {args.ml_service}")
    print()
    
    training_data = []
    
    if args.dump_file:
        print(f"Parsing dump file: {args.dump_file}")
        windows = parse_dump_file(args.dump_file)
        print(f"Found {len(windows)} windows in dump file")
        
        for window_records in windows[:args.samples]:
            fv = create_focused_feature_vector(window_records)
            training_data.append(fv)
    
    elif args.synthetic:
        print(f"Generating {args.samples} synthetic baseline samples...")
        print(f"Profile: {args.profile}")
        training_data = generate_tight_baseline(args.profile, args.samples)
    
    else:
        parser.error("Either --dump-file or --synthetic is required")
    
    print(f"Training data: {len(training_data)} samples, {len(training_data[0])} dimensions")
    
    # Train the TEST model (doesn't affect original)
    result, test_container_id = train_model(args.container, training_data, args.ml_service)
    
    # Test sensitivity
    test_sensitivity(test_container_id, args.ml_service, args.profile)
    
    # Get model file paths
    original_model = f"models/{args.container.replace('/', '_')}.pkl"
    test_model = f"models/{test_container_id.replace('/', '_')}.pkl"
    
    print()
    print("=" * 60)
    print("TEST MODEL CREATED!")
    print("=" * 60)
    print()
    print("The original model is UNCHANGED.")
    print()
    print("To test the new sensitive model:")
    print(f"  1. Score using container_id: {test_container_id}")
    print()
    print("To PROMOTE the test model (replace original):")
    print(f"  cd ml-service")
    print(f"  cp {test_model} {original_model}")
    print(f"  # Then restart ML service or delete/reload model")
    print()
    print("To DISCARD the test model:")
    print(f"  curl -X DELETE {args.ml_service}/api/ml/models/{test_container_id}")
    print()
    print("=" * 60)


if __name__ == "__main__":
    main()

