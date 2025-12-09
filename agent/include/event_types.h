#pragma once

#include <cstdint>

// Wire/event structure emitted from eBPF to user space.
struct KernelSyscallEvent {
    uint64_t ts_ns;
    uint32_t pid;
    uint32_t tid;
    uint32_t cgroup_id;
    uint16_t syscall_id;
    uint8_t arg_class;
    uint8_t arg_bucket;
};

// Compact trace record (binary) for long dumps.
struct TraceRecord {
    uint32_t delta_ts_us;
    uint16_t syscall_id;
    uint8_t arg_class;
    uint8_t arg_bucket;
} __attribute__((packed));

// Binary header for dump files.
struct TraceHeader {
    uint32_t version;
    uint32_t syscall_vocab_size;
    char container_id[64];
    uint64_t start_ts_ns;
} __attribute__((packed));

// User-space representation for short-window JSON payload.
struct SyscallEvent {
    uint64_t tsNs;
    uint32_t pid;
    uint32_t tid;
    uint64_t cgroupId;
    uint16_t syscallId;
    uint8_t argClass;
    uint8_t argBucket;
};

