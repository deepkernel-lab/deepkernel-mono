#!/usr/bin/env python3
"""
DeepKernel - Test Model with Real Data

Extracts samples from a real dump file and tests scoring against the trained model.
This validates that the model correctly recognizes data similar to its training set.

Usage:
    python test_with_real_data.py <dump_file> --container-id <id>
"""

import argparse
import sys
from pathlib import Path

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
    FEATURE_DIM
)


def score_vector(ml_service_url: str, container_id: str, feature_vector: list) -> dict:
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


def main():
    parser = argparse.ArgumentParser(
        description="Test model with real data from dump file"
    )
    parser.add_argument("dump_file", type=Path, help="Path to dump file")
    parser.add_argument("-c", "--container-id", required=True, help="Container ID")
    parser.add_argument("--ml-service-url", default="http://localhost:8081", help="ML service URL")
    parser.add_argument("--samples", type=int, default=10, help="Number of samples to test")
    parser.add_argument("--window-sec", type=int, default=5, help="Window size in seconds")
    parser.add_argument("--min-records", type=int, default=20, help="Min records per window")
    
    args = parser.parse_args()
    
    if not args.dump_file.exists():
        print(f"❌ Dump file not found: {args.dump_file}")
        sys.exit(1)
    
    print("="*60)
    print("Testing Model with REAL Data from Dump")
    print("="*60)
    print(f"Dump file:   {args.dump_file}")
    print(f"Container:   {args.container_id}")
    print()
    
    # Parse dump
    print("📂 Parsing dump file...")
    header, records = parse_dump_file(args.dump_file)
    print(f"   Records: {len(records):,}")
    
    # Split into windows
    windows = split_into_windows(records, args.window_sec * 1_000_000)
    valid_windows = [w for w in windows if len(w) >= args.min_records]
    print(f"   Valid windows: {len(valid_windows)}")
    
    if len(valid_windows) < args.samples:
        print(f"⚠️  Only {len(valid_windows)} windows available")
        args.samples = len(valid_windows)
    
    # Test samples
    print()
    print(f"🔍 Testing {args.samples} samples from REAL data:")
    print("-"*60)
    
    scores = []
    anomalous_count = 0
    
    for i in range(args.samples):
        # Take windows from different parts of the dump
        idx = i * (len(valid_windows) // args.samples)
        window = valid_windows[idx]
        
        # Extract features
        fv = extract_features(window)
        
        # Score
        result = score_vector(args.ml_service_url, args.container_id, fv)
        score = result.get('score', 0)
        anomalous = result.get('anomalous', False)
        
        scores.append(score)
        if anomalous:
            anomalous_count += 1
        
        status = "⚠️ ANOMALOUS" if anomalous else "✅ NORMAL"
        print(f"   Window {idx:4d}: score={score:7.4f}  {status}")
    
    # Summary
    print()
    print("="*60)
    print("SUMMARY")
    print("="*60)
    avg_score = sum(scores) / len(scores)
    min_score = min(scores)
    max_score = max(scores)
    
    print(f"   Samples tested:    {args.samples}")
    print(f"   Flagged anomalous: {anomalous_count} ({100*anomalous_count/args.samples:.1f}%)")
    print(f"   Score range:       {min_score:.4f} to {max_score:.4f}")
    print(f"   Average score:     {avg_score:.4f}")
    print()
    
    if anomalous_count == 0:
        print("✅ All real data samples recognized as NORMAL")
        print("   Model is working correctly!")
    elif anomalous_count < args.samples * 0.2:
        print("✅ Most real data recognized as NORMAL")
        print(f"   {anomalous_count} outliers (expected with contamination=0.1)")
    else:
        print("⚠️  Many real samples flagged as anomalous!")
        print("   Possible issues:")
        print("   1. Threshold too high (try lowering ANOMALY_THRESHOLD)")
        print("   2. Model needs retraining with more data")
        print("   3. Dump file is from different time period than training")


if __name__ == "__main__":
    main()

