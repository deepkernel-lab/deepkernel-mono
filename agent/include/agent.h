#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <unordered_map>

#include <bpf/libbpf.h>

#include "agent_server.h"
#include "buffer_manager.h"
#include "config.h"
#include "docker_mapper.h"
#include "event_types.h"
#include "http_client.h"

// Forward declaration for policy enforcer
class PolicyEnforcer;

class Agent {
public:
    explicit Agent(AgentConfig config);
    ~Agent();

    int run();
    void requestLongDump(const std::string& containerId, int durationSec);

private:
    AgentConfig config_;
    HttpClient http_;
    DockerMapper dockerMapper_;
    std::unique_ptr<AgentServer> server_;
    std::unique_ptr<PolicyEnforcer> policyEnforcer_;
    int bpf_fd_{-1};
    std::unique_ptr<ring_buffer, decltype(&ring_buffer__free)> ringbuf_{nullptr, &ring_buffer__free};
    std::unique_ptr<bpf_link, decltype(&bpf_link__destroy)> tp_link_{nullptr, &bpf_link__destroy};
    std::unique_ptr<bpf_object, decltype(&bpf_object__close)> bpf_obj_{nullptr, &bpf_object__close};
    std::unordered_map<std::string, ContainerBuffer> buffers_;
    std::atomic<bool> stop_{false};
    uint64_t lastBufferCleanupNs_{0};

    bool initBpf();
    void cleanupBpf();
    void initServer();
    static int handleEventThunk(void* ctx, void* data, size_t len);
    int handleEvent(const KernelSyscallEvent& evt);
    std::string mapContainerId(uint64_t cgroupId, uint32_t pid);
    void processShortWindow(ContainerBuffer& buffer);
    void appendLongDump(ContainerBuffer& buffer, const SyscallEvent& evt);
    void stopExpiredLongDumps(uint64_t nowNs);
    void notifyDumpComplete(ContainerBuffer& buffer);
    void cleanupInactiveBuffers(uint64_t nowNs);

    // Handler for incoming policy from server
    bool handlePolicy(const std::string& containerId, const std::string& policyId,
                      const std::string& policyType, const std::string& specJson);
};

