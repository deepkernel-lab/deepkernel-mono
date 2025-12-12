#!/usr/bin/env python3
"""
DeepKernel - Analyze Training Data

Shows what syscall patterns the model was trained on.
Helps understand why synthetic patterns may not match.

Usage:
    python analyze_training_data.py <dump_file>
"""

import argparse
import sys
from pathlib import Path
from collections import Counter

sys.path.insert(0, str(Path(__file__).parent.parent))
from tools.train_from_dump import parse_dump_file, FEATURE_DIM

# Syscall names (common x86_64)
SYSCALL_NAMES = {
    0: "read", 1: "write", 2: "open", 3: "close", 4: "stat", 5: "fstat",
    6: "lstat", 7: "poll", 8: "lseek", 9: "mmap", 10: "mprotect",
    11: "munmap", 12: "brk", 13: "rt_sigaction", 14: "rt_sigprocmask",
    17: "pread64", 18: "pwrite64", 21: "access", 22: "pipe",
    23: "select", 24: "sched_yield", 28: "madvise", 32: "dup",
    35: "nanosleep", 39: "getpid", 41: "socket", 42: "connect",
    43: "accept", 44: "sendto", 45: "recvfrom", 46: "sendmsg",
    47: "recvmsg", 48: "shutdown", 49: "bind", 50: "listen",
    56: "clone", 57: "fork", 59: "execve", 60: "exit", 61: "wait4",
    62: "kill", 72: "fcntl", 78: "getdents", 79: "getcwd",
    80: "chdir", 82: "rename", 83: "mkdir", 84: "rmdir", 87: "unlink",
    89: "readlink", 90: "chmod", 91: "fchmod", 92: "chown",
    101: "ptrace", 102: "getuid", 104: "getgid", 105: "setuid",
    107: "geteuid", 110: "getppid", 131: "sigaltstack",
    158: "arch_prctl", 202: "futex", 218: "set_tid_address",
    228: "clock_gettime", 231: "exit_group", 232: "epoll_wait",
    233: "epoll_ctl", 257: "openat", 262: "newfstatat", 288: "accept4",
    291: "epoll_create1", 302: "prlimit64", 318: "getrandom",
}

ARG_CLASS_NAMES = {0: "OTHER", 1: "FILE", 2: "NET", 3: "PROC", 4: "MEM"}


def main():
    parser = argparse.ArgumentParser(description="Analyze training dump data")
    parser.add_argument("dump_file", type=Path, help="Path to dump file")
    parser.add_argument("--top", type=int, default=20, help="Top N syscalls to show")
    
    args = parser.parse_args()
    
    if not args.dump_file.exists():
        print(f"❌ File not found: {args.dump_file}")
        sys.exit(1)
    
    print("="*70)
    print(f"Analyzing: {args.dump_file.name}")
    print("="*70)
    
    header, records = parse_dump_file(args.dump_file)
    
    print(f"\n📋 Header:")
    print(f"   Container: {header.container_id}")
    print(f"   Records:   {len(records):,}")
    
    # Syscall distribution
    syscall_counts = Counter(r.syscall_id for r in records)
    class_counts = Counter(r.arg_class for r in records)
    
    # Transitions
    transitions = Counter()
    for i in range(1, len(records)):
        prev = records[i-1].syscall_id
        curr = records[i].syscall_id
        transitions[(prev, curr)] += 1
    
    print(f"\n📊 Top {args.top} Syscalls:")
    print("-"*70)
    print(f"{'Syscall':<6} {'Name':<20} {'Count':>10} {'Percent':>10}")
    print("-"*70)
    
    for syscall_id, count in syscall_counts.most_common(args.top):
        name = SYSCALL_NAMES.get(syscall_id, f"unknown_{syscall_id}")
        pct = 100.0 * count / len(records)
        print(f"{syscall_id:<6} {name:<20} {count:>10,} {pct:>9.2f}%")
    
    print(f"\n📂 Class Distribution:")
    print("-"*50)
    for cls in sorted(class_counts.keys()):
        count = class_counts[cls]
        pct = 100.0 * count / len(records)
        name = ARG_CLASS_NAMES.get(cls, f"UNKNOWN({cls})")
        bar = "█" * int(pct / 2)
        print(f"   {name:<6}: {count:>10,} ({pct:>5.1f}%) {bar}")
    
    print(f"\n🔄 Top 15 Transitions:")
    print("-"*70)
    for (prev, curr), count in transitions.most_common(15):
        prev_name = SYSCALL_NAMES.get(prev, str(prev))[:12]
        curr_name = SYSCALL_NAMES.get(curr, str(curr))[:12]
        pct = 100.0 * count / len(records)
        print(f"   {prev_name:>12} → {curr_name:<12}: {count:>8,} ({pct:>5.2f}%)")
    
    print(f"\n💡 What quick_score.py assumes vs YOUR container:")
    print("-"*70)
    print(f"   quick_score.py 'normal':  read, write, openat, close (generic)")
    print(f"   YOUR container top 3:     ", end="")
    top3 = [SYSCALL_NAMES.get(s, str(s)) for s, _ in syscall_counts.most_common(3)]
    print(", ".join(top3))
    
    if syscall_counts.most_common(1)[0][0] not in [0, 1, 2, 3, 257]:
        print(f"\n   ⚠️  Your container's top syscall is NOT in quick_score.py's 'normal' set!")
        print(f"      This explains why synthetic 'normal' is flagged as anomalous.")


if __name__ == "__main__":
    main()

