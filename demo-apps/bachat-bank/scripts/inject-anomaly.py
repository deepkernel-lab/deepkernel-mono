#!/usr/bin/env python3
"""
DeepKernel Demo: Anomaly Injection Script

This script generates syscalls that are FOREIGN to the trained model:
- execve, fork, clone (process creation - NOT in training data)
- mmap, mprotect (memory operations - NOT in training data)  
- ptrace (debugging - highly suspicious)
- socket flood to random ports

Run inside the container to generate anomalous syscall patterns.

Usage: python3 inject-anomaly.py [--duration 30] [--intensity high]
"""

import argparse
import ctypes
import os
import socket
import subprocess
import sys
import threading
import time
import mmap
import random

def log(msg: str):
    print(f"[ANOMALY] {msg}", flush=True)

def flood_process_syscalls(duration: int, intensity: str):
    """Generate execve, fork, clone, wait - syscalls NOT in normal training."""
    log("Starting PROCESS syscall flood (execve, fork, pipe)...")
    
    count = 0
    iterations = {"low": 10, "medium": 50, "high": 200}[intensity]
    end_time = time.time() + duration
    
    while time.time() < end_time:
        for _ in range(iterations):
            try:
                # execve - process execution (VERY anomalous for a web backend)
                subprocess.run(["/bin/true"], capture_output=True, timeout=1)
                count += 1
                
                # More shell commands - each creates execve + fork + wait
                subprocess.run(["/bin/echo", "x"], capture_output=True, timeout=1)
                count += 1
                
                # Pipe operations
                subprocess.run(["/bin/sh", "-c", "echo test | cat"], capture_output=True, timeout=1)
                count += 1
                
            except Exception:
                pass
        time.sleep(0.1)
    
    log(f"PROCESS flood complete: {count} operations")

def flood_memory_syscalls(duration: int, intensity: str):
    """Generate mmap, mprotect, munmap - memory syscalls NOT in training."""
    log("Starting MEMORY syscall flood (mmap, mprotect)...")
    
    count = 0
    sizes = {"low": 10, "medium": 50, "high": 200}[intensity]
    end_time = time.time() + duration
    
    while time.time() < end_time:
        for _ in range(sizes):
            try:
                # mmap - allocate memory
                size = random.randint(4096, 65536)
                m = mmap.mmap(-1, size, mmap.MAP_PRIVATE | mmap.MAP_ANONYMOUS, 
                             mmap.PROT_READ | mmap.PROT_WRITE)
                m.write(b"X" * min(size, 1000))
                m.close()  # munmap
                count += 1
            except Exception:
                pass
        time.sleep(0.1)
    
    log(f"MEMORY flood complete: {count} allocations")

def flood_file_syscalls(duration: int, intensity: str):
    """Generate openat, read, lseek on unusual paths - NOT normal for web backend."""
    log("Starting FILE syscall flood (openat on /proc, /sys)...")
    
    count = 0
    paths = [
        "/etc/passwd",
        "/etc/shadow",  # Will fail but generates syscall
        "/etc/hosts",
        "/proc/self/maps",
        "/proc/self/status",
        "/proc/self/cmdline",
        "/proc/cpuinfo",
        "/proc/meminfo",
        "/sys/class/net/eth0/address",
        "/var/log/syslog",
    ]
    
    iterations = {"low": 5, "medium": 20, "high": 100}[intensity]
    end_time = time.time() + duration
    
    while time.time() < end_time:
        for _ in range(iterations):
            for path in paths:
                try:
                    with open(path, "r") as f:
                        f.read(100)
                    count += 1
                except Exception:
                    count += 1  # Still generates openat syscall even on failure
        time.sleep(0.1)
    
    log(f"FILE flood complete: {count} file operations")

def flood_network_syscalls(duration: int, intensity: str):
    """Generate connect to many random ports - data exfiltration pattern."""
    log("Starting NETWORK syscall flood (connect to random high ports)...")
    
    count = 0
    ports_per_batch = {"low": 5, "medium": 20, "high": 100}[intensity]
    targets = [
        ("1.1.1.1", range(4000, 4100)),      # External high ports
        ("8.8.8.8", range(5000, 5100)),      # DNS server, weird ports
        ("10.0.0.1", range(6000, 6100)),     # Internal scan pattern
        ("127.0.0.1", range(9000, 9100)),    # Localhost unusual ports
    ]
    
    end_time = time.time() + duration
    
    while time.time() < end_time:
        for host, port_range in targets:
            for port in random.sample(list(port_range), min(ports_per_batch, len(port_range))):
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    sock.settimeout(0.1)
                    sock.connect((host, port))  # Will likely fail but generates connect syscall
                    sock.close()
                except Exception:
                    pass
                count += 1
        time.sleep(0.05)
    
    log(f"NETWORK flood complete: {count} connection attempts")

def flood_suspicious_syscalls(duration: int, intensity: str):
    """Generate highly suspicious syscalls like ptrace (debugging)."""
    log("Starting SUSPICIOUS syscall flood...")
    
    count = 0
    end_time = time.time() + duration
    
    libc = None
    try:
        libc = ctypes.CDLL("libc.so.6", use_errno=True)
    except Exception:
        log("Could not load libc for ptrace calls")
        return
    
    PTRACE_TRACEME = 0
    
    while time.time() < end_time:
        try:
            # ptrace - debugging syscall (VERY suspicious for production)
            libc.ptrace(PTRACE_TRACEME, 0, 0, 0)
            count += 1
        except Exception:
            count += 1
        time.sleep(0.1)
    
    log(f"SUSPICIOUS flood complete: {count} ptrace attempts")

def main():
    parser = argparse.ArgumentParser(description="Generate anomalous syscalls for DeepKernel demo")
    parser.add_argument("--duration", type=int, default=30, help="Duration in seconds (default: 30)")
    parser.add_argument("--intensity", choices=["low", "medium", "high"], default="high",
                       help="Intensity level (default: high)")
    parser.add_argument("--type", choices=["all", "process", "memory", "file", "network", "suspicious"],
                       default="all", help="Type of anomaly to generate (default: all)")
    args = parser.parse_args()
    
    log(f"Starting anomaly injection: duration={args.duration}s, intensity={args.intensity}, type={args.type}")
    log("=" * 60)
    
    threads = []
    flood_functions = {
        "process": flood_process_syscalls,
        "memory": flood_memory_syscalls,
        "file": flood_file_syscalls,
        "network": flood_network_syscalls,
        "suspicious": flood_suspicious_syscalls,
    }
    
    if args.type == "all":
        # Run all floods in parallel for maximum impact
        for name, func in flood_functions.items():
            t = threading.Thread(target=func, args=(args.duration, args.intensity))
            t.start()
            threads.append(t)
    else:
        func = flood_functions[args.type]
        t = threading.Thread(target=func, args=(args.duration, args.intensity))
        t.start()
        threads.append(t)
    
    # Wait for all threads
    for t in threads:
        t.join()
    
    log("=" * 60)
    log("Anomaly injection complete!")
    log("The ML model should now detect significantly anomalous behavior.")

if __name__ == "__main__":
    main()

