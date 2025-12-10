#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <unordered_map>

#include <bpf/libbpf.h>

#include "buffer_manager.h"
#include "config.h"
#include "event_types.h"
#include "http_client.h"

class Agent {
public:
    explicit Agent(AgentConfig config);
    ~Agent();

    int run();
    void requestLongDump(const std::string& containerId, int durationSec);

private:
    AgentConfig config_;
    HttpClient http_;
    int bpf_fd_{-1};
    std::unique_ptr<ring_buffer, decltype(&ring_buffer__free)> ringbuf_{nullptr, &ring_buffer__free};
    std::unique_ptr<bpf_link, decltype(&bpf_link__destroy)> tp_link_{nullptr, &bpf_link__destroy};
    std::unique_ptr<bpf_object, decltype(&bpf_object__close)> bpf_obj_{nullptr, &bpf_object__close};
    std::unordered_map<std::string, ContainerBuffer> buffers_;
    std::atomic<bool> stop_{false};

    bool initBpf();
    void cleanupBpf();
    static int handleEventThunk(void* ctx, void* data, size_t len);
    int handleEvent(const KernelSyscallEvent& evt);
    std::string mapContainerId(uint64_t cgroupId) const;
    void processShortWindow(ContainerBuffer& buffer);
    void appendLongDump(ContainerBuffer& buffer, const SyscallEvent& evt);
    void stopExpiredLongDumps(uint64_t nowNs);
};

