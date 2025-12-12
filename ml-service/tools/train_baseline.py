#!/usr/bin/env python3
"""
DeepKernel Baseline Training Utility

For demo/testing: Train a model using synthetic baseline data that mimics
normal web application behavior (read/write/network syscalls).

Usage:
    python train_baseline.py --container-id bachat-backend
    python train_baseline.py --container-id bachat-frontend --samples 200
"""

import argparse
import random
import sys
import math
from typing import List
import httpx

# Feature dimensions
K = 24
BUCKETS = 12
FEATURE_DIM = 594


def generate_normal_web_app_features(variation_seed: int = 0) -> List[float]:
    """
    Generate a feature vector that mimics normal web application behavior.
    
    Normal behavior profile:
    - Mostly read/write file operations
    - Some network (connect, recv, send)
    - Occasional process operations
    - Low entropy (predictable patterns)
    """
    random.seed(variation_seed)
    
    # Create a "normal" transition matrix
    # Web apps typically: read -> process -> write -> network -> read ...
    transitions = [[0.0] * K for _ in range(K)]
    
    # Common syscall indices (from map_syscall_to_alphabet):
    # 1=open, 2=read, 3=write, 4=close, 6=connect, 7=accept, 8=send, 9=recv
    
    # Normal patterns: read-heavy, some writes, occasional network
    common_syscalls = [1, 2, 3, 4, 6, 9, 23]  # open, read, write, close, connect, recv, other
    
    # Build transition probabilities with some randomness
    for from_idx in common_syscalls:
        total = 0.0
        for to_idx in common_syscalls:
            # Higher probability for common transitions
            if from_idx == 1 and to_idx == 2:  # open -> read
                transitions[from_idx][to_idx] = 0.7 + random.uniform(-0.1, 0.1)
            elif from_idx == 2 and to_idx in [2, 3, 4]:  # read -> read/write/close
                transitions[from_idx][to_idx] = 0.3 + random.uniform(-0.05, 0.05)
            elif from_idx == 3 and to_idx in [2, 3, 4]:  # write -> read/write/close
                transitions[from_idx][to_idx] = 0.3 + random.uniform(-0.05, 0.05)
            elif from_idx == 4 and to_idx in [1, 6]:  # close -> open/connect
                transitions[from_idx][to_idx] = 0.4 + random.uniform(-0.1, 0.1)
            elif from_idx == 6 and to_idx == 9:  # connect -> recv
                transitions[from_idx][to_idx] = 0.8 + random.uniform(-0.1, 0.1)
            elif from_idx == 9 and to_idx in [2, 3, 4]:  # recv -> read/write/close
                transitions[from_idx][to_idx] = 0.3 + random.uniform(-0.05, 0.05)
            else:
                transitions[from_idx][to_idx] = random.uniform(0, 0.1)
            total += transitions[from_idx][to_idx]
        
        # Normalize row
        if total > 0:
            for to_idx in range(K):
                transitions[from_idx][to_idx] /= total
    
    # Flatten transition matrix
    vec = []
    unique_two_grams = 0
    entropy = 0.0
    
    for i in range(K):
        for j in range(K):
            v = transitions[i][j]
            vec.append(v)
            if v > 0:
                unique_two_grams += 1
                entropy -= v * math.log(v + 1e-12)
    
    # Statistics (normal web app profile)
    file_ratio = 0.6 + random.uniform(-0.1, 0.1)  # Mostly file ops
    net_ratio = 0.2 + random.uniform(-0.05, 0.05)  # Some network
    duration_sec = 5.0 + random.uniform(-1, 1)  # ~5 second windows
    mean_inter_arrival = 0.01 + random.uniform(-0.005, 0.005)  # 10ms average
    
    vec.extend([
        float(unique_two_grams),
        entropy,
        file_ratio,
        net_ratio,
        duration_sec,
        mean_inter_arrival
    ])
    
    # Bucket distribution (normalized)
    bucket_counts = [random.uniform(0, 0.2) for _ in range(BUCKETS)]
    total_buckets = sum(bucket_counts)
    for b in range(BUCKETS):
        vec.append(bucket_counts[b] / total_buckets if total_buckets > 0 else 1.0/BUCKETS)
    
    # Pad to FEATURE_DIM
    while len(vec) < FEATURE_DIM:
        vec.append(0.0)
    
    return vec[:FEATURE_DIM]


def train_model(
    ml_service_url: str,
    container_id: str,
    training_data: List[List[float]],
    reason: str = "synthetic_baseline"
) -> dict:
    """Send training data to the ML service."""
    url = f"{ml_service_url}/api/ml/train"
    payload = {
        "container_id": container_id,
        "training_data": training_data,
        "context": {
            "reason": reason,
            "min_records_per_window": 20
        }
    }
    
    with httpx.Client(timeout=60.0) as client:
        response = client.post(url, json=payload)
        response.raise_for_status()
        return response.json()


def check_health(ml_service_url: str) -> bool:
    """Check if ML service is healthy."""
    try:
        with httpx.Client(timeout=5.0) as client:
            response = client.get(f"{ml_service_url}/health")
            return response.status_code == 200
    except Exception:
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Train ML model with synthetic baseline data (for demo/testing)"
    )
    parser.add_argument("-c", "--container-id", required=True, help="Container identifier")
    parser.add_argument("--ml-service-url", default="http://localhost:8081", help="ML service URL")
    parser.add_argument("--samples", type=int, default=100, help="Number of training samples")
    parser.add_argument("--reason", default="synthetic_baseline", help="Training reason")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    
    args = parser.parse_args()
    
    if args.samples < 10:
        print("Error: Need at least 10 samples for training", file=sys.stderr)
        sys.exit(1)
    
    # Generate training data
    print(f"🔧 Generating {args.samples} synthetic baseline feature vectors...")
    print(f"   Simulating normal web application behavior")
    
    training_data = []
    for i in range(args.samples):
        features = generate_normal_web_app_features(args.seed + i)
        training_data.append(features)
    
    print(f"   Generated {len(training_data)} × {FEATURE_DIM}-dim vectors")
    
    # Check ML service health
    print(f"\n🔗 Connecting to ML service: {args.ml_service_url}")
    if not check_health(args.ml_service_url):
        print("Error: ML service is not reachable. Is it running?", file=sys.stderr)
        print("   Start with: cd ml-service && python -m src.main", file=sys.stderr)
        sys.exit(1)
    print("   ML service is healthy")
    
    # Train model
    print(f"\n🎯 Training model for container: {args.container_id}")
    
    try:
        result = train_model(
            args.ml_service_url,
            args.container_id,
            training_data,
            args.reason
        )
        print(f"\n✅ Training complete!")
        print(f"   Model ID:      {result.get('model_id')}")
        print(f"   Version:       {result.get('version')}")
        print(f"   Status:        {result.get('status')}")
        print(f"   Sample count:  {result.get('sample_count')}")
        print(f"   Trained at:    {result.get('trained_at')}")
    except httpx.HTTPStatusError as e:
        print(f"Error: Training failed: {e.response.text}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: Training failed: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()

