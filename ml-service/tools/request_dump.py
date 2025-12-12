#!/usr/bin/env python3
"""
DeepKernel - Request Long Dump from Agent

Sends a request to the agent to start a long syscall dump for training.

Usage:
    python request_dump.py --container-id bachat-backend --duration 900
    python request_dump.py -c bachat-backend -d 1200 --agent-url http://localhost:7070

This requires the DeepKernel agent to be running with HTTP server enabled.
"""

import argparse
import sys
import time

try:
    import httpx
except ImportError:
    print("Error: httpx not installed. Run: pip install httpx")
    sys.exit(1)


def check_agent_health(agent_url: str) -> bool:
    """Check if agent is running and healthy."""
    try:
        with httpx.Client(timeout=5.0) as client:
            resp = client.get(f"{agent_url}/health")
            if resp.status_code == 200:
                data = resp.json()
                print(f"✅ Agent is healthy")
                print(f"   Agent ID: {data.get('agent_id', 'unknown')}")
                print(f"   Uptime: {data.get('uptime_seconds', 'unknown')}s")
                print(f"   Containers monitored: {data.get('containers_monitored', 'unknown')}")
                return True
    except httpx.ConnectError:
        print(f"❌ Cannot connect to agent at {agent_url}")
        print(f"   Is the agent running with HTTP server enabled?")
        print(f"   Check: DK_AGENT_LISTEN_PORT environment variable")
    except Exception as e:
        print(f"❌ Error checking agent health: {e}")
    return False


def request_long_dump(agent_url: str, container_id: str, duration_sec: int, reason: str) -> dict:
    """
    Request the agent to start a long dump.
    
    Args:
        agent_url: Agent HTTP server URL
        container_id: Container to dump
        duration_sec: Dump duration in seconds
        reason: Reason for dump
    
    Returns:
        Response from agent
    """
    url = f"{agent_url}/long-dump-requests"
    payload = {
        "container_id": container_id,
        "duration_sec": duration_sec,
        "reason": reason
    }
    
    with httpx.Client(timeout=30.0) as client:
        resp = client.post(url, json=payload)
        resp.raise_for_status()
        return resp.json()


def format_duration(seconds: int) -> str:
    """Format duration in human-readable format."""
    if seconds < 60:
        return f"{seconds} seconds"
    elif seconds < 3600:
        mins = seconds // 60
        secs = seconds % 60
        return f"{mins} min {secs} sec" if secs else f"{mins} minutes"
    else:
        hours = seconds // 3600
        mins = (seconds % 3600) // 60
        return f"{hours}h {mins}m"


def main():
    parser = argparse.ArgumentParser(
        description="Request long syscall dump from DeepKernel agent",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Request 15-minute dump for backend container
  python request_dump.py -c bachat-backend -d 900

  # Request 20-minute dump with custom agent URL
  python request_dump.py -c my-container -d 1200 --agent-url http://192.168.1.10:7070

  # Request baseline training dump
  python request_dump.py -c prod-api -d 1200 --reason INITIAL_TRAINING

Duration recommendations:
  - Quick test:     60 seconds   (1 minute)
  - Short baseline: 300 seconds  (5 minutes)
  - Normal:         900 seconds  (15 minutes)  ← Recommended
  - Full baseline:  1200 seconds (20 minutes)
        """
    )
    parser.add_argument("-c", "--container-id", required=True,
                        help="Container ID to dump (must match agent filter)")
    parser.add_argument("-d", "--duration", type=int, default=900,
                        help="Dump duration in seconds (default: 900 = 15 min)")
    parser.add_argument("--agent-url", default="http://localhost:7070",
                        help="Agent HTTP server URL (default: http://localhost:7070)")
    parser.add_argument("--reason", default="BASELINE_TRAINING",
                        choices=["INITIAL_TRAINING", "BASELINE_TRAINING", "RETRAIN", "DIAGNOSTIC"],
                        help="Reason for dump (default: BASELINE_TRAINING)")
    parser.add_argument("--no-wait", action="store_true",
                        help="Don't wait for dump to complete")
    
    args = parser.parse_args()
    
    print("="*60)
    print("DeepKernel - Request Long Dump")
    print("="*60)
    print(f"Agent URL:    {args.agent_url}")
    print(f"Container:    {args.container_id}")
    print(f"Duration:     {format_duration(args.duration)}")
    print(f"Reason:       {args.reason}")
    print()
    
    # Check agent health
    if not check_agent_health(args.agent_url):
        sys.exit(1)
    
    print()
    
    # Request dump
    try:
        print(f"📤 Requesting long dump...")
        result = request_long_dump(
            args.agent_url,
            args.container_id,
            args.duration,
            args.reason
        )
        
        print(f"✅ Dump request accepted!")
        print(f"   Status: {result.get('status')}")
        print(f"   Container: {result.get('container_id', args.container_id)}")
        print(f"   Duration: {format_duration(result.get('duration_sec', args.duration))}")
        
        dump_path = result.get('dump_path')
        if dump_path:
            print(f"   Dump file: {dump_path}")
        
        if not args.no_wait:
            print()
            print(f"⏳ Dump will complete in {format_duration(args.duration)}...")
            print(f"   Started at: {time.strftime('%Y-%m-%d %H:%M:%S')}")
            
            end_time = time.strftime('%Y-%m-%d %H:%M:%S', 
                                     time.localtime(time.time() + args.duration))
            print(f"   Expected completion: {end_time}")
            print()
            print("   Next steps after dump completes:")
            print(f"   1. Agent will notify server at /api/v1/agent/dump-complete")
            print(f"   2. Use train_from_dump.py to train model:")
            if dump_path:
                print(f"      python tools/train_from_dump.py {dump_path} -c {args.container_id}")
            else:
                print(f"      python tools/train_from_dump.py <dump_file> -c {args.container_id}")
        
        print()
        print("✅ Done! Dump is now recording.")
        
    except httpx.HTTPStatusError as e:
        print(f"❌ Request failed: {e.response.status_code}")
        try:
            error_body = e.response.json()
            print(f"   Error: {error_body}")
        except:
            print(f"   Response: {e.response.text}")
        sys.exit(1)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()

