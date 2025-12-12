// DeepKernel eBPF program: captures sys_enter tracepoint and emits compact
// syscall events to a ring buffer.

#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>

// Argument class constants (must match server-side definitions)
#define ARG_CLASS_OTHER  0
#define ARG_CLASS_FILE   1
#define ARG_CLASS_NET    2
#define ARG_CLASS_PROC   3
#define ARG_CLASS_MEM    4

// Minimal struct matching the user-space dk_syscall_event_t.
struct dk_syscall_event_t {
    __u64 ts_ns;
    __u32 pid;
    __u32 tid;
    __u32 cgroup_id;
    __u16 syscall_id;
    __u8 arg_class;
    __u8 arg_bucket;
};

// Ring buffer map for events.
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 256 * 1024);
} events SEC(".maps");

// Classify syscall into FILE, NET, PROC, MEM, or OTHER category.
// Based on x86_64 syscall numbers from /usr/include/asm/unistd_64.h
static __always_inline __u8 classify_arg_class(long syscall_id) {
    // FILE operations (arg_class = 1)
    // Core I/O: read(0), write(1), open(2), close(3), stat(4), fstat(5), lstat(6)
    // Polling/seeking: poll(7), lseek(8), select(23), pselect6(270)
    // Advanced I/O: pread64(17), pwrite64(18), readv(19), writev(20)
    // File access: access(21), pipe(22), pipe2(293), dup(32), dup2(33), dup3(292)
    // File control: fcntl(72), flock(73), ioctl(16)
    // Sync: fsync(74), fdatasync(75), sync(162), syncfs(306)
    // Size: truncate(76), ftruncate(77)
    // Directory: getdents(78), getdents64(217), getcwd(79), chdir(80), fchdir(81)
    // File ops: rename(82), mkdir(83), rmdir(84), creat(85), link(86), unlink(87)
    // Symlinks: symlink(88), readlink(89)
    // Permissions: chmod(90), fchmod(91), chown(92), fchown(93), lchown(94), umask(95)
    // Async I/O: epoll_create(213), epoll_ctl(233), epoll_wait(232), epoll_pwait(281)
    //            epoll_create1(291), epoll_pwait2(441)
    // inotify: inotify_init(253), inotify_add_watch(254), inotify_rm_watch(255), inotify_init1(294)
    // *at syscalls: openat(257), mkdirat(258), mknodat(259), fchownat(260), futimesat(261)
    //               newfstatat(262), unlinkat(263), renameat(264), linkat(265), symlinkat(266)
    //               readlinkat(267), fchmodat(268), faccessat(269), renameat2(316), statx(332)
    // eventfd: eventfd(284), eventfd2(290), timerfd_create(283), timerfd_settime(286), timerfd_gettime(287)
    // io_uring: io_uring_setup(425), io_uring_enter(426), io_uring_register(427)
    if (syscall_id == 0 || syscall_id == 1 || syscall_id == 2 || syscall_id == 3 ||
        syscall_id == 4 || syscall_id == 5 || syscall_id == 6 ||
        syscall_id == 7 || syscall_id == 8 || syscall_id == 16 || // ioctl
        syscall_id == 17 || syscall_id == 18 || syscall_id == 19 || syscall_id == 20 ||
        syscall_id == 21 || syscall_id == 22 || syscall_id == 23 || // select
        syscall_id == 32 || syscall_id == 33 ||
        syscall_id == 72 || syscall_id == 73 || syscall_id == 74 || syscall_id == 75 ||
        syscall_id == 76 || syscall_id == 77 || syscall_id == 78 ||
        syscall_id == 79 || syscall_id == 80 || syscall_id == 81 ||
        syscall_id == 82 || syscall_id == 83 || syscall_id == 84 || syscall_id == 85 ||
        syscall_id == 86 || syscall_id == 87 || syscall_id == 88 ||
        syscall_id == 89 || syscall_id == 90 || syscall_id == 91 ||
        syscall_id == 92 || syscall_id == 93 || syscall_id == 94 || syscall_id == 95 ||
        syscall_id == 162 || // sync
        syscall_id == 213 || // epoll_create
        syscall_id == 217 || // getdents64
        syscall_id == 232 || syscall_id == 233 || // epoll_wait, epoll_ctl
        syscall_id == 253 || syscall_id == 254 || syscall_id == 255 || // inotify
        (syscall_id >= 257 && syscall_id <= 270) || // *at syscalls + pselect6
        syscall_id == 281 || // epoll_pwait
        syscall_id == 283 || syscall_id == 284 || // timerfd, eventfd
        syscall_id == 286 || syscall_id == 287 || // timerfd_settime/gettime
        syscall_id == 290 || syscall_id == 291 || syscall_id == 292 || syscall_id == 293 || syscall_id == 294 ||
        syscall_id == 306 || syscall_id == 316 || syscall_id == 332 ||
        syscall_id == 425 || syscall_id == 426 || syscall_id == 427 || // io_uring
        syscall_id == 441) { // epoll_pwait2
        return ARG_CLASS_FILE;
    }

    // NETWORK operations (arg_class = 2)
    // socket(41), connect(42), accept(43), sendto(44), recvfrom(45)
    // sendmsg(46), recvmsg(47), shutdown(48), bind(49), listen(50)
    // getsockname(51), getpeername(52), socketpair(53), setsockopt(54)
    // getsockopt(55), accept4(288), recvmmsg(299), sendmmsg(307)
    if ((syscall_id >= 41 && syscall_id <= 55) ||
        syscall_id == 288 || syscall_id == 299 || syscall_id == 307) {
        return ARG_CLASS_NET;
    }

    // PROCESS operations (arg_class = 3)
    // Process creation: clone(56), fork(57), vfork(58), execve(59), clone3(435), execveat(322)
    // Process exit: exit(60), exit_group(231), wait4(61), waitid(247)
    // Signals: kill(62), tkill(200), tgkill(234), rt_sigaction(13), rt_sigprocmask(14)
    //          rt_sigreturn(15), sigaltstack(131), rt_sigsuspend(133)
    // Process info: getpid(39), gettid(186), getppid(110), getpgid(121), setpgid(109)
    //               getsid(124), setsid(112), getuid(102), geteuid(107), getgid(104), getegid(108)
    //               setuid(105), setgid(106), setreuid(113), setregid(114)
    // Process control: prctl(157), ptrace(101), arch_prctl(158)
    // Scheduling: sched_yield(24), sched_getaffinity(204), sched_setaffinity(203)
    // Capabilities: capget(125), capset(126)
    // Namespaces: unshare(272), setns(308)
    if (syscall_id == 13 || syscall_id == 14 || syscall_id == 15 || // rt_sig*
        syscall_id == 24 || // sched_yield
        syscall_id == 39 || // getpid
        syscall_id == 56 || syscall_id == 57 || syscall_id == 58 ||
        syscall_id == 59 || syscall_id == 60 || syscall_id == 61 ||
        syscall_id == 62 ||
        syscall_id == 101 || syscall_id == 102 || // ptrace, getuid
        syscall_id == 104 || syscall_id == 105 || syscall_id == 106 || syscall_id == 107 || syscall_id == 108 ||
        syscall_id == 109 || syscall_id == 110 ||
        syscall_id == 112 || syscall_id == 113 || syscall_id == 114 ||
        syscall_id == 121 || syscall_id == 124 || syscall_id == 125 || syscall_id == 126 ||
        syscall_id == 131 || syscall_id == 133 ||
        syscall_id == 157 || syscall_id == 158 ||
        syscall_id == 186 || // gettid
        syscall_id == 200 || syscall_id == 203 || syscall_id == 204 ||
        syscall_id == 231 || syscall_id == 234 || syscall_id == 247 ||
        syscall_id == 272 || syscall_id == 308 ||
        syscall_id == 322 || syscall_id == 435) {
        return ARG_CLASS_PROC;
    }

    // MEMORY operations (arg_class = 4)
    // Core: mmap(9), mprotect(10), munmap(11), brk(12), mremap(25), msync(26)
    // Info: mincore(27), madvise(28)
    // Shared memory: shmget(29), shmat(30), shmctl(31), shmdt(67)
    // Locking: mlock(149), munlock(150), mlockall(151), munlockall(152), mlock2(325)
    // Advanced: memfd_create(319), membarrier(324), process_madvise(440)
    // Futex (thread sync): futex(202), futex_waitv(449)
    // set_tid_address(218) - thread setup
    if (syscall_id == 9 || syscall_id == 10 || syscall_id == 11 || syscall_id == 12 ||
        syscall_id == 25 || syscall_id == 26 || syscall_id == 27 || syscall_id == 28 ||
        syscall_id == 29 || syscall_id == 30 || syscall_id == 31 || syscall_id == 67 ||
        syscall_id == 149 || syscall_id == 150 || syscall_id == 151 || syscall_id == 152 ||
        syscall_id == 202 || // futex
        syscall_id == 218 || // set_tid_address
        syscall_id == 319 || syscall_id == 324 || syscall_id == 325 ||
        syscall_id == 440 || syscall_id == 449) {
        return ARG_CLASS_MEM;
    }

    // Everything else is OTHER (time, random, misc)
    // Includes: clock_gettime(228), clock_nanosleep(230), nanosleep(35), 
    //           getrandom(318), time(201), gettimeofday(96)
    return ARG_CLASS_OTHER;
}

// SEC("tracepoint/syscalls/sys_enter")
SEC("tracepoint/raw_syscalls/sys_enter")
int handle_sys_enter(struct trace_event_raw_sys_enter *ctx) {
    struct dk_syscall_event_t *evt;
    __u64 pid_tgid = bpf_get_current_pid_tgid();

    evt = bpf_ringbuf_reserve(&events, sizeof(*evt), 0);
    if (!evt) {
        return 0;
    }

    evt->ts_ns = bpf_ktime_get_ns();
    evt->pid = pid_tgid >> 32; // tgid
    evt->tid = pid_tgid & 0xFFFFFFFF;
    evt->cgroup_id = bpf_get_current_cgroup_id();
    evt->syscall_id = (__u16)ctx->id;
    evt->arg_class = classify_arg_class(ctx->id);
    evt->arg_bucket = 0; // TODO: derive bucket (e.g., net/file buckets) when available.

    bpf_ringbuf_submit(evt, 0);
    return 0;
}

char _license[] SEC("license") = "Dual BSD/GPL";

