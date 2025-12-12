#!/usr/bin/env python3
"""
DeepKernel - Validate Agent Dump File

Validates that an agent-generated dump file is correctly formatted
and compatible with the ML training pipeline.

Usage:
    python validate_dump.py <dump_file>
"""

import argparse
import struct
import sys
from pathlib import Path
from collections import Counter

# Expected formats (must match agent/include/event_types.h)
HEADER_FORMAT = "<II64sQ"  # version, vocab_size, container_id[64], start_ts_ns
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)  # Should be 80 bytes

RECORD_FORMAT = "<IHBB"  # delta_ts_us, syscall_id, arg_class, arg_bucket
RECORD_SIZE = struct.calcsize(RECORD_FORMAT)  # Should be 8 bytes

# Syscall class names
ARG_CLASS_NAMES = {
    0: "OTHER",
    1: "FILE", 
    2: "NET",
    3: "PROC",
    4: "MEM"
}


def validate_dump(filepath: Path) -> bool:
    """Validate a dump file and print diagnostics."""
    
    print(f"\n{'='*60}")
    print(f"Validating: {filepath}")
    print(f"{'='*60}")
    
    if not filepath.exists():
        print(f"❌ File not found: {filepath}")
        return False
    
    file_size = filepath.stat().st_size
    print(f"\n📁 File size: {file_size:,} bytes")
    
    if file_size < HEADER_SIZE:
        print(f"❌ File too small for header (need {HEADER_SIZE} bytes)")
        return False
    
    try:
        with open(filepath, "rb") as f:
            # Read and validate header
            header_bytes = f.read(HEADER_SIZE)
            version, vocab_size, container_bytes, start_ts = struct.unpack(
                HEADER_FORMAT, header_bytes
            )
            
            container_id = container_bytes.rstrip(b'\x00').decode('utf-8', errors='replace')
            
            print(f"\n📋 Header ({HEADER_SIZE} bytes):")
            print(f"   Version:        {version}")
            print(f"   Vocab size:     {vocab_size}")
            print(f"   Container ID:   '{container_id}'")
            print(f"   Start timestamp: {start_ts} ns")
            
            # Validate header
            if version != 1:
                print(f"   ⚠️  Unexpected version (expected 1)")
            else:
                print(f"   ✅ Version OK")
            
            if vocab_size not in [256, 512]:
                print(f"   ⚠️  Unusual vocab size")
            else:
                print(f"   ✅ Vocab size OK")
            
            if not container_id:
                print(f"   ⚠️  Empty container ID")
            else:
                print(f"   ✅ Container ID OK")
            
            # Calculate expected records
            data_size = file_size - HEADER_SIZE
            expected_records = data_size // RECORD_SIZE
            remainder = data_size % RECORD_SIZE
            
            print(f"\n📊 Records:")
            print(f"   Data section:   {data_size:,} bytes")
            print(f"   Record size:    {RECORD_SIZE} bytes")
            print(f"   Expected count: {expected_records:,}")
            
            if remainder != 0:
                print(f"   ⚠️  {remainder} extra bytes (possible corruption)")
            else:
                print(f"   ✅ No trailing bytes")
            
            # Read and analyze records
            syscall_counts = Counter()
            class_counts = Counter()
            delta_values = []
            
            records_read = 0
            while True:
                record_bytes = f.read(RECORD_SIZE)
                if len(record_bytes) < RECORD_SIZE:
                    break
                
                delta_ts, syscall_id, arg_class, arg_bucket = struct.unpack(
                    RECORD_FORMAT, record_bytes
                )
                
                syscall_counts[syscall_id] += 1
                class_counts[arg_class] += 1
                delta_values.append(delta_ts)
                records_read += 1
            
            print(f"   Records read:   {records_read:,}")
            
            if records_read != expected_records:
                print(f"   ⚠️  Expected {expected_records}, got {records_read}")
            else:
                print(f"   ✅ Record count matches")
            
            # Time analysis
            if delta_values:
                total_time_us = sum(delta_values)
                total_time_sec = total_time_us / 1_000_000
                avg_delta = total_time_us / len(delta_values)
                
                print(f"\n⏱️ Time Analysis:")
                print(f"   Total duration: {total_time_sec:.2f} seconds")
                print(f"   Avg delta:      {avg_delta:.2f} µs")
                print(f"   Syscalls/sec:   {records_read / total_time_sec:.1f}")
            
            # Syscall distribution
            print(f"\n🔍 Top 10 Syscalls:")
            for syscall_id, count in syscall_counts.most_common(10):
                pct = 100.0 * count / records_read
                print(f"   {syscall_id:4d}: {count:8,} ({pct:5.1f}%)")
            
            # Class distribution
            print(f"\n📂 Class Distribution:")
            for cls in sorted(class_counts.keys()):
                count = class_counts[cls]
                pct = 100.0 * count / records_read
                name = ARG_CLASS_NAMES.get(cls, f"UNKNOWN({cls})")
                print(f"   {name:6s}: {count:8,} ({pct:5.1f}%)")
            
            # Validation summary
            print(f"\n{'='*60}")
            print("VALIDATION SUMMARY")
            print(f"{'='*60}")
            
            issues = []
            if version != 1:
                issues.append("Unexpected version")
            if not container_id:
                issues.append("Empty container ID")
            if remainder != 0:
                issues.append("Trailing bytes")
            if records_read < 100:
                issues.append("Very few records")
            
            if not issues:
                print("✅ Dump file is VALID and compatible with ML service")
                return True
            else:
                print("⚠️  Issues found:")
                for issue in issues:
                    print(f"   - {issue}")
                return len([i for i in issues if "Trailing" in i or "version" in i]) == 0
            
    except Exception as e:
        print(f"❌ Error reading file: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Validate agent dump file format"
    )
    parser.add_argument("dump_file", type=Path, help="Path to .dkdump file")
    
    args = parser.parse_args()
    
    valid = validate_dump(args.dump_file)
    sys.exit(0 if valid else 1)


if __name__ == "__main__":
    main()

