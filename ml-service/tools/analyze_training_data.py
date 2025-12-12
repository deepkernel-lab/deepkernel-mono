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

# Syscall names (comprehensive x86_64)
SYSCALL_NAMES = {
    0: "read", 1: "write", 2: "open", 3: "close", 4: "stat", 5: "fstat",
    6: "lstat", 7: "poll", 8: "lseek", 9: "mmap", 10: "mprotect",
    11: "munmap", 12: "brk", 13: "rt_sigaction", 14: "rt_sigprocmask",
    15: "rt_sigreturn", 16: "ioctl", 17: "pread64", 18: "pwrite64",
    19: "readv", 20: "writev", 21: "access", 22: "pipe", 23: "select",
    24: "sched_yield", 25: "mremap", 26: "msync", 27: "mincore",
    28: "madvise", 29: "shmget", 30: "shmat", 31: "shmctl", 32: "dup",
    33: "dup2", 35: "nanosleep", 39: "getpid", 41: "socket", 42: "connect",
    43: "accept", 44: "sendto", 45: "recvfrom", 46: "sendmsg",
    47: "recvmsg", 48: "shutdown", 49: "bind", 50: "listen",
    51: "getsockname", 52: "getpeername", 53: "socketpair",
    54: "setsockopt", 55: "getsockopt",
    56: "clone", 57: "fork", 58: "vfork", 59: "execve", 60: "exit",
    61: "wait4", 62: "kill", 67: "shmdt", 72: "fcntl", 73: "flock",
    74: "fsync", 75: "fdatasync", 76: "truncate", 77: "ftruncate",
    78: "getdents", 79: "getcwd", 80: "chdir", 81: "fchdir",
    82: "rename", 83: "mkdir", 84: "rmdir", 85: "creat", 86: "link",
    87: "unlink", 88: "symlink", 89: "readlink", 90: "chmod",
    91: "fchmod", 92: "chown", 93: "fchown", 94: "lchown", 95: "umask",
    96: "gettimeofday", 97: "getrlimit", 99: "sysinfo",
    101: "ptrace", 102: "getuid", 104: "getgid", 105: "setuid",
    106: "setgid", 107: "geteuid", 108: "getegid", 109: "setpgid",
    110: "getppid", 112: "setsid", 113: "setreuid", 114: "setregid",
    121: "getpgid", 124: "getsid", 125: "capget", 126: "capset",
    131: "sigaltstack", 133: "rt_sigsuspend",
    149: "mlock", 150: "munlock", 151: "mlockall", 152: "munlockall",
    157: "prctl", 158: "arch_prctl", 162: "sync",
    186: "gettid", 200: "tkill", 201: "time", 202: "futex",
    203: "sched_setaffinity", 204: "sched_getaffinity",
    213: "epoll_create", 217: "getdents64", 218: "set_tid_address",
    228: "clock_gettime", 229: "clock_getres", 230: "clock_nanosleep",
    231: "exit_group", 232: "epoll_wait", 233: "epoll_ctl", 234: "tgkill",
    247: "waitid", 253: "inotify_init", 254: "inotify_add_watch",
    255: "inotify_rm_watch", 257: "openat", 258: "mkdirat",
    259: "mknodat", 260: "fchownat", 261: "futimesat", 262: "newfstatat",
    263: "unlinkat", 264: "renameat", 265: "linkat", 266: "symlinkat",
    267: "readlinkat", 268: "fchmodat", 269: "faccessat", 270: "pselect6",
    272: "unshare", 281: "epoll_pwait", 283: "timerfd_create",
    284: "eventfd", 286: "timerfd_settime", 287: "timerfd_gettime",
    288: "accept4", 290: "eventfd2", 291: "epoll_create1", 292: "dup3",
    293: "pipe2", 294: "inotify_init1", 299: "recvmmsg", 302: "prlimit64",
    306: "syncfs", 307: "sendmmsg", 308: "setns", 316: "renameat2",
    318: "getrandom", 319: "memfd_create", 322: "execveat", 324: "membarrier",
    325: "mlock2", 332: "statx", 425: "io_uring_setup", 426: "io_uring_enter",
    427: "io_uring_register", 435: "clone3", 440: "process_madvise",
    441: "epoll_pwait2", 449: "futex_waitv",
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

