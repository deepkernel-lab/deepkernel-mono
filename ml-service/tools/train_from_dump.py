#!/usr/bin/env python3
"""
DeepKernel Training Utility

Converts agent binary long dump files into feature vectors and trains
the ML service's Isolation Forest model.

Usage:
    python train_from_dump.py <dump_file> --container-id <id> [--ml-service-url <url>]
    
Example:
    python train_from_dump.py /var/deepkernel/dumps/backend.bin --container-id bachat-backend
"""

import argparse
import struct
import sys
import math
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple, Optional
import httpx


# ==============================================================================
# Binary Dump Parsing
# ==============================================================================

@dataclass
class TraceHeader:
    """Binary dump file header."""
    version: int
    syscall_vocab_size: int
    container_id: str
    start_ts_ns: int


@dataclass
class TraceRecord:
    """Single syscall record from dump."""
    delta_ts_us: int
    syscall_id: int
    arg_class: int
    arg_bucket: int


# Header format: uint32 version, uint32 vocab_size, char[64] container_id, uint64 start_ts
HEADER_FORMAT = "<II64sQ"
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)  # 80 bytes

# Record format: uint32 delta_ts_us, uint16 syscall_id, uint8 arg_class, uint8 arg_bucket
RECORD_FORMAT = "<IHBB"
RECORD_SIZE = struct.calcsize(RECORD_FORMAT)  # 8 bytes


def parse_dump_file(filepath: Path) -> Tuple[TraceHeader, List[TraceRecord]]:
    """
    Parse a binary dump file from the agent.
    
    Returns:
        Tuple of (header, list of records)
    """
    with open(filepath, "rb") as f:
        # Read header
        header_bytes = f.read(HEADER_SIZE)
        if len(header_bytes) < HEADER_SIZE:
            raise ValueError(f"File too small for header: {len(header_bytes)} bytes")
        
        version, vocab_size, container_bytes, start_ts = struct.unpack(HEADER_FORMAT, header_bytes)
        container_id = container_bytes.rstrip(b'\x00').decode('utf-8', errors='replace')
        
        header = TraceHeader(
            version=version,
            syscall_vocab_size=vocab_size,
            container_id=container_id,
            start_ts_ns=start_ts
        )
        
        # Read all records
        records = []
        while True:
            record_bytes = f.read(RECORD_SIZE)
            if len(record_bytes) < RECORD_SIZE:
                break
            
            delta_ts, syscall_id, arg_class, arg_bucket = struct.unpack(RECORD_FORMAT, record_bytes)
            records.append(TraceRecord(
                delta_ts_us=delta_ts,
                syscall_id=syscall_id,
                arg_class=arg_class,
                arg_bucket=arg_bucket
            ))
    
    return header, records


# ==============================================================================
# Feature Extraction (Port of Java FeatureExtractor)
# ==============================================================================

K = 24  # Alphabet size for syscall mapping
BUCKETS = 12
FEATURE_DIM = 594


def map_syscall_to_alphabet(syscall_id: int) -> int:
    """Map syscall ID to 0-23 alphabet index."""
    mapping = {
        59: 0,   # execve
        2: 1, 257: 1,  # open, openat
        0: 2,   # read
        1: 3,   # write
        3: 4,   # close
        4: 5, 5: 5,  # stat, fstat
        42: 6,  # connect
        43: 7,  # accept
        44: 8, 46: 8,  # sendto, sendmsg
        45: 9, 47: 9,  # recvfrom, recvmsg
        41: 10, 49: 10, 50: 10,  # socket, bind, listen
        57: 11, 56: 11,  # fork, clone
        9: 12, 10: 12,  # mmap, mprotect
        90: 13, 91: 13,  # chmod, fchmod
        87: 14, 84: 14,  # unlink, rmdir
        82: 15,  # rename
        102: 16, 105: 16,  # getuid, setuid
        160: 17,  # setrlimit
        101: 18,  # ptrace
        165: 19, 166: 19,  # mount, umount
        12: 20,  # brk
        78: 21,  # getdents
        162: 22,  # nanosleep
    }
    return mapping.get(syscall_id, 23)  # 23 = OTHER


def map_bucket(arg_class: int, arg_bucket: int) -> int:
    """Map arg_class and arg_bucket to bucket index."""
    idx = (arg_class * 4 + arg_bucket) % BUCKETS
    return max(0, min(idx, BUCKETS - 1))


def extract_features(records: List[TraceRecord], window_start_ts_us: int = 0) -> List[float]:
    """
    Extract 594-dimensional feature vector from a window of syscall records.
    
    Matches the Java FeatureExtractor implementation.
    """
    if not records:
        return [0.0] * FEATURE_DIM
    
    # Initialize
    transitions = [[0.0] * K for _ in range(K)]
    outgoing = [0] * K
    bucket_counts = [0] * BUCKETS
    file_ops = net_ops = proc_ops = other_ops = 0
    
    total = len(records)
    first_ts = window_start_ts_us
    last_ts = first_ts
    
    for i, r in enumerate(records):
        curr_id = map_syscall_to_alphabet(r.syscall_id)
        bucket = map_bucket(r.arg_class, r.arg_bucket)
        bucket_counts[bucket] += 1
        
        # Category counts (based on arg_class from eBPF)
        if r.arg_class == 1:  # FILE
            file_ops += 1
        elif r.arg_class == 2:  # NET
            net_ops += 1
        elif r.arg_class == 3:  # PROC
            proc_ops += 1
        else:
            other_ops += 1
        
        # Timestamp
        ts = window_start_ts_us + r.delta_ts_us
        last_ts = ts
        
        # Transitions
        if i > 0:
            prev_id = map_syscall_to_alphabet(records[i - 1].syscall_id)
            transitions[prev_id][curr_id] += 1.0
            outgoing[prev_id] += 1
    
    # Normalize transition rows
    for i in range(K):
        if outgoing[i] > 0:
            for j in range(K):
                transitions[i][j] /= outgoing[i]
    
    # Build feature vector
    vec = []
    unique_two_grams = 0
    entropy = 0.0
    total_transitions = max(total - 1, 1)
    
    # Transition matrix (K*K = 576 features)
    for i in range(K):
        for j in range(K):
            v = transitions[i][j]
            vec.append(v)
            if v > 0:
                unique_two_grams += 1
                count = v * outgoing[i]
                p = count / total_transitions
                entropy -= p * math.log(p + 1e-12)
    
    # Statistics (6 features)
    file_ratio = file_ops / total if total > 0 else 0.0
    net_ratio = net_ops / total if total > 0 else 0.0
    duration_sec = (last_ts - first_ts) / 1_000_000.0  # us to sec
    mean_inter_arrival = duration_sec / total if total > 0 else 0.0
    
    vec.extend([
        float(unique_two_grams),
        entropy,
        file_ratio,
        net_ratio,
        duration_sec,
        mean_inter_arrival
    ])
    
    # Bucket ratios (12 features)
    bucket_total = max(total, 1)
    for b in range(BUCKETS):
        vec.append(bucket_counts[b] / bucket_total)
    
    # Pad if needed
    while len(vec) < FEATURE_DIM:
        vec.append(0.0)
    
    return vec[:FEATURE_DIM]


def split_into_windows(
    records: List[TraceRecord], 
    window_duration_us: int = 5_000_000  # 5 seconds default
) -> List[List[TraceRecord]]:
    """
    Split records into time-based windows.
    
    Args:
        records: List of trace records
        window_duration_us: Window size in microseconds (default 5 sec)
    
    Returns:
        List of windows, each containing records for that time window
    """
    if not records:
        return []
    
    windows = []
    current_window = []
    window_start = 0
    cumulative_ts = 0
    
    for r in records:
        cumulative_ts += r.delta_ts_us
        
        # Check if we've exceeded the window
        while cumulative_ts - window_start >= window_duration_us:
            if current_window:
                windows.append(current_window)
            current_window = []
            window_start += window_duration_us
        
        current_window.append(r)
    
    # Don't forget the last window
    if current_window:
        windows.append(current_window)
    
    return windows


# ==============================================================================
# ML Service Client
# ==============================================================================

def train_model(
    ml_service_url: str,
    container_id: str,
    training_data: List[List[float]],
    reason: str = "baseline_training"
) -> dict:
    """
    Send training data to the ML service.
    
    Args:
        ml_service_url: Base URL of ML service
        container_id: Container identifier
        training_data: List of 594-dim feature vectors
        reason: Training reason for context
    
    Returns:
        Response from ML service
    """
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


# ==============================================================================
# Main
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Train ML model from agent binary dump file",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Train from dump file
  python train_from_dump.py /var/deepkernel/dumps/backend.bin --container-id bachat-backend
  
  # Custom ML service URL and window size
  python train_from_dump.py dump.bin -c my-container --ml-service-url http://localhost:8081 --window-sec 10
  
  # Just analyze the dump (dry run)
  python train_from_dump.py dump.bin -c my-container --dry-run
        """
    )
    parser.add_argument("dump_file", type=Path, help="Path to binary dump file")
    parser.add_argument("-c", "--container-id", required=True, help="Container identifier")
    parser.add_argument("--ml-service-url", default="http://localhost:8081", help="ML service URL")
    parser.add_argument("--window-sec", type=int, default=5, help="Window duration in seconds")
    parser.add_argument("--min-records", type=int, default=20, help="Minimum records per window")
    parser.add_argument("--reason", default="baseline_training", help="Training reason")
    parser.add_argument("--dry-run", action="store_true", help="Analyze without training")
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    
    args = parser.parse_args()
    
    # Validate dump file exists
    if not args.dump_file.exists():
        print(f"Error: Dump file not found: {args.dump_file}", file=sys.stderr)
        sys.exit(1)
    
    # Parse dump file
    print(f" Reading dump file: {args.dump_file}")
    try:
        header, records = parse_dump_file(args.dump_file)
    except Exception as e:
        print(f"Error parsing dump file: {e}", file=sys.stderr)
        sys.exit(1)
    
    print(f"   Header version: {header.version}")
    print(f"   Container ID:   {header.container_id}")
    print(f"   Start timestamp: {header.start_ts_ns}")
    print(f"   Total records:  {len(records):,}")
    
    if len(records) == 0:
        print("Error: No records in dump file", file=sys.stderr)
        sys.exit(1)
    
    # Split into windows
    window_duration_us = args.window_sec * 1_000_000
    windows = split_into_windows(records, window_duration_us)
    print(f"\n Split into {len(windows)} windows ({args.window_sec}s each)")
    
    # Filter windows with minimum records
    valid_windows = [w for w in windows if len(w) >= args.min_records]
    print(f"   Valid windows (≥{args.min_records} records): {len(valid_windows)}")
    
    if len(valid_windows) < 10:
        print(f"Warning: Only {len(valid_windows)} valid windows. ML training requires at least 10.", file=sys.stderr)
        if not args.dry_run:
            sys.exit(1)
    
    # Extract features
    print(f"\n Extracting {FEATURE_DIM}-dim feature vectors...")
    training_data = []
    for i, window in enumerate(valid_windows):
        features = extract_features(window)
        training_data.append(features)
        if args.verbose:
            print(f"   Window {i+1}: {len(window)} records → feature vector extracted")
    
    print(f"   Generated {len(training_data)} feature vectors")
    
    if args.verbose:
        # Show some stats about the features
        import statistics
        all_vals = [v for fv in training_data for v in fv]
        print(f"   Feature stats: min={min(all_vals):.4f}, max={max(all_vals):.4f}, "
              f"mean={statistics.mean(all_vals):.4f}")
    
    if args.dry_run:
        print("\n Dry run complete. No training performed.")
        return
    
    # Check ML service health
    print(f"\n Connecting to ML service: {args.ml_service_url}")
    if not check_health(args.ml_service_url):
        print("Error: ML service is not reachable", file=sys.stderr)
        sys.exit(1)
    print("   ML service is healthy")
    
    # Train model
    print(f"\n Training model for container: {args.container_id}")
    print(f"   Training samples: {len(training_data)}")
    print(f"   Reason: {args.reason}")
    
    try:
        result = train_model(
            args.ml_service_url,
            args.container_id,
            training_data,
            args.reason
        )
        print(f"\n Training complete!")
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

