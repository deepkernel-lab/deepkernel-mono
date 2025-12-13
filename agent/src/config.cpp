#include "config.h"

#include <cstdlib>
#include <iostream>

namespace {
std::string envOrDefault(const char* key, const std::string& fallback) {
    const char* val = std::getenv(key);
    if (val && *val) {
        return std::string(val);
    }
    return fallback;
}

int envOrDefaultInt(const char* key, int fallback) {
    const char* val = std::getenv(key);
    if (val && *val) {
        return std::atoi(val);
    }
    return fallback;
}

bool envOrDefaultBool(const char* key, bool fallback) {
    const char* val = std::getenv(key);
    if (val && *val) {
        std::string s(val);
        return s == "1" || s == "true" || s == "yes" || s == "on";
    }
    return fallback;
}
}  // namespace

AgentConfig loadConfig() {
    AgentConfig cfg;

    // Basic agent identification
    cfg.agentId = envOrDefault("DK_AGENT_ID", cfg.agentId);
    cfg.serverUrl = envOrDefault("DK_SERVER_URL", cfg.serverUrl);
    cfg.nodeName = envOrDefault("DK_NODE_NAME", cfg.nodeName);

    // Window settings
    cfg.shortWindowSec = envOrDefaultInt("DK_SHORT_WINDOW_SEC", cfg.shortWindowSec);
    cfg.sendIntervalSec = envOrDefaultInt("DK_SEND_INTERVAL_SEC", cfg.sendIntervalSec);
    cfg.minEventsPerWindow = envOrDefaultInt("DK_MIN_EVENTS_PER_WINDOW", cfg.minEventsPerWindow);

    // Long dump settings
    cfg.longDumpDefaultDurationSec =
        envOrDefaultInt("DK_LONG_DUMP_DURATION_SEC", cfg.longDumpDefaultDurationSec);
    cfg.dumpDir = envOrDefault("DK_DUMP_DIR", cfg.dumpDir);
    cfg.syscallVocabSize = envOrDefaultInt("DK_SYSCALL_VOCAB_SIZE", cfg.syscallVocabSize);
    cfg.autoBaselineDump = envOrDefaultBool("DK_AUTO_BASELINE_DUMP", cfg.autoBaselineDump);

    // Container runtime integration
    // For Docker environments (default): use legacy fast mapper
    // For K8s with containerd/crio: set DK_USE_LEGACY_MAPPER=false
    cfg.useLegacyDockerMapper = envOrDefaultBool("DK_USE_LEGACY_MAPPER", cfg.useLegacyDockerMapper);
    cfg.containerRuntime = envOrDefault("DK_CONTAINER_RUNTIME", cfg.containerRuntime);
    cfg.dockerSocketPath = envOrDefault("DK_DOCKER_SOCKET", cfg.dockerSocketPath);
    cfg.containerdSocketPath = envOrDefault("DK_CONTAINERD_SOCKET", cfg.containerdSocketPath);
    cfg.crioSocketPath = envOrDefault("DK_CRIO_SOCKET", cfg.crioSocketPath);
    cfg.crictlPath = envOrDefault("DK_CRICTL_PATH", cfg.crictlPath);
    cfg.containerMapCacheTTL = envOrDefaultInt("DK_CONTAINER_CACHE_TTL", cfg.containerMapCacheTTL);

    // Kubernetes settings (only used when DK_USE_LEGACY_MAPPER=false)
    cfg.enableKubernetesApi = envOrDefaultBool("DK_ENABLE_K8S_API", cfg.enableKubernetesApi);
    cfg.preferPodName = envOrDefaultBool("DK_PREFER_POD_NAME", cfg.preferPodName);

    // Agent HTTP server
    cfg.agentListenPort = envOrDefaultInt("DK_AGENT_LISTEN_PORT", cfg.agentListenPort);

    // Container filtering
    cfg.containerFilterRegex = envOrDefault("DK_CONTAINER_FILTER", cfg.containerFilterRegex);

    // Policy settings
    cfg.policyDir = envOrDefault("DK_POLICY_DIR", cfg.policyDir);
    cfg.policyEnforcementMode = envOrDefault("DK_POLICY_ENFORCEMENT_MODE", cfg.policyEnforcementMode);

    return cfg;
}

