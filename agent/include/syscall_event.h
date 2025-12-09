#pragma once

#include <cstdint>

// User-space representation of a syscall event emitted by the eBPF program.
struct SyscallEvent {
    uint64_t tsNs;
    uint32_t pid;
    uint32_t tid;
    uint64_t cgroupId;
    uint16_t syscallId;
    uint8_t argClass;
    uint8_t argBucket;
};

