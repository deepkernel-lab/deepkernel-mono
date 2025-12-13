#!/usr/bin/env python3
"""
DeepKernel - Model Statistics Inspector

View detailed statistics for trained models.

Usage:
    python model_stats.py --container-id bachat-backend
    python model_stats.py --list
"""

import argparse
import sys
import json

try:
    import httpx
except ImportError:
    print("Error: httpx not installed. Run: pip install httpx")
    sys.exit(1)


def get_model_stats(ml_service_url: str, container_id: str) -> dict:
    """Get detailed model stats."""
    with httpx.Client(timeout=30.0) as client:
        resp = client.get(f"{ml_service_url}/api/ml/models/{container_id}/stats")
        resp.raise_for_status()
        return resp.json()


def get_model_meta(ml_service_url: str, container_id: str) -> dict:
    """Get model metadata."""
    with httpx.Client(timeout=10.0) as client:
        resp = client.get(f"{ml_service_url}/api/ml/models/{container_id}")
        resp.raise_for_status()
        return resp.json()


def list_models(ml_service_url: str) -> list:
    """List all models."""
    with httpx.Client(timeout=10.0) as client:
        resp = client.get(f"{ml_service_url}/api/ml/models")
        resp.raise_for_status()
        return resp.json()


def format_bytes(size: int) -> str:
    """Format bytes in human readable form."""
    if size is None:
        return "N/A"
    for unit in ['B', 'KB', 'MB', 'GB']:
        if abs(size) < 1024.0:
            return f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size:.1f} TB"


def print_model_stats(stats: dict):
    """Pretty print model statistics."""
    print()
    print("="*60)
    print(f"Model Statistics: {stats.get('container_id')}")
    print("="*60)
    
    # Basic info
    print("\n Basic Info:")
    print(f"   Model ID:       {stats.get('model_id')}")
    print(f"   Container:      {stats.get('container_id')}")
    print(f"   Version:        {stats.get('version')}")
    print(f"   Status:         {stats.get('status')}")
    
    # Training info
    print("\n Training Info:")
    print(f"   Trained at:     {stats.get('trained_at') or 'Never'}")
    print(f"   Sample count:   {stats.get('sample_count') or 'N/A'}")
    
    # Model parameters
    print("\n Model Parameters:")
    print(f"   n_estimators:   {stats.get('n_estimators')}")
    print(f"   contamination:  {stats.get('contamination')}")
    print(f"   max_samples:    {stats.get('max_samples')}")
    
    # Model internals
    print("\n Model Internals:")
    offset = stats.get('offset')
    print(f"   Decision offset: {f'{offset:.4f}' if offset is not None else 'N/A'}")
    
    fi_mean = stats.get('feature_importances_mean')
    fi_std = stats.get('feature_importances_std')
    print(f"   Feature importance mean: {f'{fi_mean:.6f}' if fi_mean is not None else 'N/A'}")
    print(f"   Feature importance std:  {f'{fi_std:.6f}' if fi_std is not None else 'N/A'}")
    
    # Scoring
    print("\n Scoring Configuration:")
    print(f"   Anomaly threshold: {stats.get('anomaly_threshold')}")
    print(f"   (scores < threshold are anomalous)")
    
    # Storage
    print("\n Storage:")
    print(f"   Model file: {stats.get('model_file_path') or 'Not persisted'}")
    print(f"   File size:  {format_bytes(stats.get('model_file_size_bytes'))}")
    
    print()


def print_model_list(models: list):
    """Print model list in table format."""
    print()
    print("="*80)
    print("Registered Models")
    print("="*80)
    
    if not models:
        print("  No models registered")
        return
    
    # Table header
    print(f"{'Container':<30} {'Version':<10} {'Status':<12} {'Samples':<10} {'Trained':<20}")
    print("-"*80)
    
    for m in models:
        container = m.get('container_id', 'unknown')[:30]
        version = str(m.get('version', 0))
        status = m.get('status', 'UNKNOWN')
        samples = str(m.get('sample_count') or '-')
        trained = m.get('trained_at', '-')
        if trained and trained != '-':
            trained = trained[:19]  # Truncate timestamp
        
        status_emoji = "✅" if status == "READY" else "⏳" if status == "TRAINING" else "❌"
        print(f"{container:<30} {version:<10} {status_emoji} {status:<10} {samples:<10} {trained:<20}")
    
    print()
    print(f"Total: {len(models)} model(s)")
    print()


def main():
    parser = argparse.ArgumentParser(
        description="Inspect DeepKernel ML model statistics"
    )
    parser.add_argument("-c", "--container-id",
                        help="Container ID to inspect")
    parser.add_argument("--ml-service-url", default="http://localhost:8081",
                        help="ML service URL")
    parser.add_argument("--list", action="store_true",
                        help="List all models")
    parser.add_argument("--json", action="store_true",
                        help="Output as JSON")
    
    args = parser.parse_args()
    
    if not args.container_id and not args.list:
        print("Error: Either --container-id or --list is required")
        parser.print_help()
        sys.exit(1)
    
    try:
        if args.list:
            models = list_models(args.ml_service_url)
            if args.json:
                print(json.dumps(models, indent=2, default=str))
            else:
                print_model_list(models)
        else:
            stats = get_model_stats(args.ml_service_url, args.container_id)
            if args.json:
                print(json.dumps(stats, indent=2, default=str))
            else:
                print_model_stats(stats)
                
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            print(f" No model found for container: {args.container_id}")
        else:
            print(f" Error: {e.response.status_code} - {e.response.text}")
        sys.exit(1)
    except httpx.ConnectError:
        print(f" Cannot connect to ML service at {args.ml_service_url}")
        print("   Is the ML service running?")
        sys.exit(1)
    except Exception as e:
        print(f" Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()

