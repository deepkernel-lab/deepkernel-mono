#!/usr/bin/env python3
"""
DeepKernel - Quick Score Test

Test scoring against trained models with synthetic data patterns.
Useful for verifying model behavior without running the full pipeline.

Usage:
    python quick_score.py --container-id bachat-backend --pattern normal
    python quick_score.py -c bachat-backend --pattern anomalous
    python quick_score.py -c bachat-backend --pattern attack
"""

import argparse
import sys
import random
from typing import List

try:
    import httpx
except ImportError:
    print("Error: httpx not installed. Run: pip install httpx")
    sys.exit(1)


# Feature dimensions
K = 24
BUCKETS = 12
FEATURE_DIM = 594


def generate_normal_features(seed: int = 42) -> List[float]:
    """Generate feature vector for NORMAL web app behavior."""
    random.seed(seed)
    
    # Transition matrix (576 features) - mostly zeros with common patterns
    transitions = [[0.0] * K for _ in range(K)]
    
    # Common patterns: read(2) -> write(3) -> close(4) -> open(1)
    transitions[1][2] = 0.7  # open -> read
    transitions[2][2] = 0.3  # read -> read
    transitions[2][3] = 0.4  # read -> write
    transitions[3][4] = 0.6  # write -> close
    transitions[4][1] = 0.5  # close -> open
    transitions[6][9] = 0.8  # connect -> recv
    transitions[9][3] = 0.5  # recv -> write
    
    # Add some noise
    for i in range(K):
        for j in range(K):
            if transitions[i][j] == 0 and random.random() < 0.05:
                transitions[i][j] = random.uniform(0, 0.1)
    
    # Flatten
    vec = []
    for i in range(K):
        for j in range(K):
            vec.append(transitions[i][j])
    
    # Stats (6 features)
    vec.extend([
        15.0,   # unique_two_grams (low - predictable)
        1.2,    # entropy (low)
        0.65,   # file_ratio (high - file heavy)
        0.15,   # net_ratio (low - minimal network)
        5.0,    # duration_sec
        0.01    # mean_inter_arrival
    ])
    
    # Bucket ratios (12 features)
    buckets = [0.3, 0.25, 0.2, 0.1, 0.05, 0.04, 0.03, 0.02, 0.01, 0.0, 0.0, 0.0]
    vec.extend(buckets)
    
    return vec[:FEATURE_DIM] + [0.0] * max(0, FEATURE_DIM - len(vec))


def generate_anomalous_features(seed: int = 999) -> List[float]:
    """Generate feature vector for ANOMALOUS behavior (attack pattern)."""
    random.seed(seed)
    
    # Transition matrix with unusual patterns
    transitions = [[0.0] * K for _ in range(K)]
    
    # Suspicious patterns: lots of execve(0), ptrace(18), mount(19)
    transitions[0][0] = 0.3   # execve -> execve (process spawning)
    transitions[0][18] = 0.4  # execve -> ptrace
    transitions[18][0] = 0.5  # ptrace -> execve
    transitions[6][6] = 0.6   # connect -> connect (many connections)
    transitions[19][14] = 0.3 # mount -> unlink
    transitions[13][1] = 0.4  # chmod -> open
    
    # High variance / chaos
    for i in range(K):
        for j in range(K):
            if random.random() < 0.15:
                transitions[i][j] = random.uniform(0.1, 0.5)
    
    vec = []
    for i in range(K):
        for j in range(K):
            vec.append(transitions[i][j])
    
    # Stats - high entropy, unusual ratios
    vec.extend([
        85.0,   # unique_two_grams (very high - chaotic)
        3.5,    # entropy (high - unpredictable)
        0.2,    # file_ratio (lower)
        0.45,   # net_ratio (high - lots of network)
        5.0,    # duration_sec
        0.002   # mean_inter_arrival (very fast)
    ])
    
    # Bucket ratios - more uniform (unusual)
    buckets = [0.1] * 10 + [0.0, 0.0]
    vec.extend(buckets)
    
    return vec[:FEATURE_DIM] + [0.0] * max(0, FEATURE_DIM - len(vec))


def generate_attack_features(attack_type: str = "exfil", seed: int = 888) -> List[float]:
    """Generate feature vector for specific attack patterns."""
    random.seed(seed)
    
    transitions = [[0.0] * K for _ in range(K)]
    
    if attack_type == "exfil":
        # Data exfiltration: read files, connect out, send data
        transitions[1][2] = 0.8   # open -> read (reading lots of files)
        transitions[2][6] = 0.5   # read -> connect (sending data)
        transitions[6][8] = 0.9   # connect -> send
        transitions[8][2] = 0.4   # send -> read (loop)
    elif attack_type == "cryptominer":
        # Crypto miner: heavy CPU, some network
        transitions[12][12] = 0.7  # mmap -> mmap (memory allocation)
        transitions[12][20] = 0.3  # mmap -> brk
        transitions[6][9] = 0.8    # connect -> recv (pool connection)
    elif attack_type == "reverse_shell":
        # Reverse shell: execve, dup2, connect
        transitions[0][0] = 0.5   # execve -> execve
        transitions[0][6] = 0.4   # execve -> connect
        transitions[6][0] = 0.3   # connect -> execve
        transitions[11][0] = 0.6  # fork -> execve
    
    vec = []
    for i in range(K):
        for j in range(K):
            vec.append(transitions[i][j])
    
    # Attack-specific stats
    vec.extend([
        45.0,   # unique_two_grams
        2.8,    # entropy
        0.3,    # file_ratio
        0.4,    # net_ratio (high network)
        5.0,    # duration
        0.005   # fast operations
    ])
    
    buckets = [0.08] * 12
    vec.extend(buckets)
    
    return vec[:FEATURE_DIM] + [0.0] * max(0, FEATURE_DIM - len(vec))


def score_vector(ml_service_url: str, container_id: str, feature_vector: List[float]) -> dict:
    """Score a feature vector."""
    with httpx.Client(timeout=30.0) as client:
        resp = client.post(
            f"{ml_service_url}/api/ml/score",
            json={
                "container_id": container_id,
                "feature_vector": feature_vector
            }
        )
        resp.raise_for_status()
        return resp.json()


def get_model_info(ml_service_url: str, container_id: str) -> dict:
    """Get model metadata."""
    with httpx.Client(timeout=10.0) as client:
        resp = client.get(f"{ml_service_url}/api/ml/models/{container_id}")
        resp.raise_for_status()
        return resp.json()


def main():
    parser = argparse.ArgumentParser(
        description="Quick score test against trained models",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Patterns:
  normal      - Typical web application (read/write/close)
  anomalous   - Generic suspicious activity (high entropy)
  exfil       - Data exfiltration pattern
  cryptominer - Cryptocurrency miner pattern
  reverse     - Reverse shell pattern

Examples:
  python quick_score.py -c bachat-backend --pattern normal
  python quick_score.py -c my-app --pattern exfil --all
        """
    )
    parser.add_argument("-c", "--container-id", required=True,
                        help="Container ID to score against")
    parser.add_argument("--ml-service-url", default="http://localhost:8081",
                        help="ML service URL")
    parser.add_argument("--pattern", default="normal",
                        choices=["normal", "anomalous", "exfil", "cryptominer", "reverse"],
                        help="Behavior pattern to generate")
    parser.add_argument("--all", action="store_true",
                        help="Test all patterns")
    parser.add_argument("--seed", type=int, default=42,
                        help="Random seed for reproducibility")
    
    args = parser.parse_args()
    
    print("="*60)
    print("DeepKernel - Quick Score Test")
    print("="*60)
    print(f"ML Service: {args.ml_service_url}")
    print(f"Container:  {args.container_id}")
    print()
    
    # Get model info
    try:
        model = get_model_info(args.ml_service_url, args.container_id)
        print(f"📊 Model Info:")
        print(f"   Status: {model.get('status')}")
        print(f"   Version: {model.get('version')}")
        print(f"   Sample count: {model.get('sample_count')}")
        
        if model.get('status') != 'READY':
            print(f"\n⚠️  Model is not trained. Train it first with train_from_dump.py or train_baseline.py")
            sys.exit(1)
        print()
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            print(f"❌ No model found for container: {args.container_id}")
            print(f"   Train a model first using train_from_dump.py or train_baseline.py")
        else:
            print(f"❌ Error: {e}")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Cannot connect to ML service: {e}")
        sys.exit(1)
    
    # Determine which patterns to test
    if args.all:
        patterns = ["normal", "anomalous", "exfil", "cryptominer", "reverse"]
    else:
        patterns = [args.pattern]
    
    # Test each pattern
    results = []
    for pattern in patterns:
        print(f"🔍 Testing pattern: {pattern}")
        
        if pattern == "normal":
            fv = generate_normal_features(args.seed)
            expected_anomalous = False
        elif pattern == "anomalous":
            fv = generate_anomalous_features(args.seed)
            expected_anomalous = True
        else:
            fv = generate_attack_features(pattern, args.seed)
            expected_anomalous = True
        
        try:
            result = score_vector(args.ml_service_url, args.container_id, fv)
            
            score = result.get('score', 0)
            anomalous = result.get('anomalous', False)
            
            # Determine if result matches expectation
            if anomalous == expected_anomalous:
                status = "✅"
                match = "CORRECT"
            else:
                status = "⚠️"
                match = "UNEXPECTED"
            
            print(f"   {status} Score: {score:.4f}, Anomalous: {anomalous} ({match})")
            
            results.append({
                'pattern': pattern,
                'score': score,
                'anomalous': anomalous,
                'expected': expected_anomalous,
                'correct': anomalous == expected_anomalous
            })
            
        except Exception as e:
            print(f"   ❌ Error: {e}")
            results.append({
                'pattern': pattern,
                'error': str(e)
            })
        
        print()
    
    # Summary
    if len(results) > 1:
        print("="*60)
        print("SUMMARY")
        print("="*60)
        correct = sum(1 for r in results if r.get('correct', False))
        total = len([r for r in results if 'error' not in r])
        print(f"   Correct predictions: {correct}/{total}")
        
        if correct == total:
            print("   ✅ All patterns classified correctly!")
        else:
            print("   ⚠️  Some patterns misclassified")
            print("      Model may need more training data or tuning")


if __name__ == "__main__":
    main()

