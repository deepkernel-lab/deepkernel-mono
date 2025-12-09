// Placeholder for DeepKernel eBPF program.
// TODO: implement syscall trace collection and perf/ring buffer emission.

#include <linux/bpf.h>

SEC("tracepoint/syscalls/sys_enter")
int handle_sys_enter(struct trace_event_raw_sys_enter *ctx) {
    // TODO: emit compact syscall event to user space.
    return 0;
}

char _license[] SEC("license") = "Dual BSD/GPL";

