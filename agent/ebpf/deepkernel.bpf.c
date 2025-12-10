// DeepKernel eBPF program: captures sys_enter tracepoint and emits compact
// syscall events to a ring buffer.

#include <linux/bpf.h>
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

// Tracepoint args definition (partial) to access syscall id.
struct trace_event_raw_sys_enter {
    struct trace_entry ent;
    long id;
    unsigned long args[6];
};

// Classify syscall into FILE, NET, PROC, MEM, or OTHER category.
// Based on x86_64 syscall numbers from /usr/include/asm/unistd_64.h
static __always_inline __u8 classify_arg_class(long syscall_id) {
    // FILE operations (arg_class = 1)
    // read(0), write(1), open(2), close(3), stat(4), fstat(5), lstat(6)
    // poll(7), lseek(8), pread64(17), pwrite64(18), readv(19), writev(20)
    // access(21), pipe(22), dup(32), dup2(33), fcntl(72), flock(73)
    // fsync(74), fdatasync(75), truncate(76), ftruncate(77), getdents(78)
    // getcwd(79), chdir(80), fchdir(81), rename(82), mkdir(83), rmdir(84)
    // creat(85), link(86), unlink(87), symlink(88), readlink(89)
    // chmod(90), fchmod(91), chown(92), fchown(93), lchown(94)
    // openat(257), mkdirat(258), mknodat(259), fchownat(260), futimesat(261)
    // newfstatat(262), unlinkat(263), renameat(264), linkat(265), symlinkat(266)
    // readlinkat(267), fchmodat(268), faccessat(269)
    // statx(332), io_uring_enter(426), io_uring_register(427)
    if (syscall_id == 0 || syscall_id == 1 || syscall_id == 2 || syscall_id == 3 ||
        syscall_id == 4 || syscall_id == 5 || syscall_id == 6 ||
        syscall_id == 7 || syscall_id == 8 ||
        syscall_id == 17 || syscall_id == 18 || syscall_id == 19 || syscall_id == 20 ||
        syscall_id == 21 || syscall_id == 22 ||
        syscall_id == 32 || syscall_id == 33 ||
        syscall_id == 72 || syscall_id == 73 || syscall_id == 74 || syscall_id == 75 ||
        syscall_id == 76 || syscall_id == 77 || syscall_id == 78 ||
        syscall_id == 79 || syscall_id == 80 || syscall_id == 81 ||
        syscall_id == 82 || syscall_id == 83 || syscall_id == 84 || syscall_id == 85 ||
        syscall_id == 86 || syscall_id == 87 || syscall_id == 88 ||
        syscall_id == 89 || syscall_id == 90 || syscall_id == 91 ||
        syscall_id == 92 || syscall_id == 93 || syscall_id == 94 ||
        (syscall_id >= 257 && syscall_id <= 269) ||
        syscall_id == 332) {
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
    // clone(56), fork(57), vfork(58), execve(59), exit(60), wait4(61)
    // kill(62), getpid(39), getppid(110), getpgid(121), setpgid(109)
    // getsid(124), setsid(112), prctl(157), ptrace(101)
    // clone3(435), execveat(322)
    if (syscall_id == 56 || syscall_id == 57 || syscall_id == 58 ||
        syscall_id == 59 || syscall_id == 60 || syscall_id == 61 ||
        syscall_id == 62 || syscall_id == 39 ||
        syscall_id == 109 || syscall_id == 110 || syscall_id == 112 ||
        syscall_id == 121 || syscall_id == 124 ||
        syscall_id == 101 || syscall_id == 157 ||
        syscall_id == 322 || syscall_id == 435) {
        return ARG_CLASS_PROC;
    }

    // MEMORY operations (arg_class = 4)
    // mmap(9), mprotect(10), munmap(11), brk(12), mremap(25), msync(26)
    // mincore(27), madvise(28), shmget(29), shmat(30), shmctl(31)
    // mlock(149), munlock(150), mlockall(151), munlockall(152)
    // memfd_create(319), mlock2(325)
    if (syscall_id == 9 || syscall_id == 10 || syscall_id == 11 || syscall_id == 12 ||
        syscall_id == 25 || syscall_id == 26 || syscall_id == 27 || syscall_id == 28 ||
        syscall_id == 29 || syscall_id == 30 || syscall_id == 31 ||
        syscall_id == 149 || syscall_id == 150 || syscall_id == 151 || syscall_id == 152 ||
        syscall_id == 319 || syscall_id == 325) {
        return ARG_CLASS_MEM;
    }

    // Everything else is OTHER
    return ARG_CLASS_OTHER;
}

SEC("tracepoint/syscalls/sys_enter")
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

