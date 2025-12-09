// DeepKernel eBPF program: captures sys_enter tracepoint and emits compact
// syscall events to a ring buffer.

#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>

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

static __always_inline __u8 classify_arg_class(long syscall_id) {
    // TODO: implement richer classification based on syscall.
    return 0;
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

