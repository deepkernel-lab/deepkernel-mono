#pragma once

#include <string>

struct AgentConfig {
    // Basic agent identification
    std::string agentId{"node-1"};
    std::string serverUrl{"http://localhost:9090"};
    std::string nodeName{"worker-01"};

    // Window settings
    int shortWindowSec{5};
    int sendIntervalSec{5};
    int minEventsPerWindow{20};

    // Long dump settings
    int longDumpDefaultDurationSec{1200};
    std::string dumpDir{"/var/lib/deepkernel/dumps"};
    int syscallVocabSize{256};
    bool autoBaselineDump{false};

    // Container runtime integration
    // Supports: docker (default, fast), containerd, crio, auto (auto-detect)
    // Set DK_CONTAINER_RUNTIME=containerd or crio for Kubernetes environments
    std::string containerRuntime{"docker"};  // Default to docker for performance
    std::string dockerSocketPath{"/var/run/docker.sock"};
    std::string containerdSocketPath{"/run/containerd/containerd.sock"};
    std::string crioSocketPath{"/var/run/crio/crio.sock"};
    std::string crictlPath{"/usr/bin/crictl"};
    int containerMapCacheTTL{60};  // seconds
    
    // Use legacy DockerMapper (faster, Docker-only) vs new ContainerMapper (multi-runtime)
    // Default: true (use fast legacy mapper for Docker environments)
    bool useLegacyDockerMapper{true};
    
    // Kubernetes settings (only used when useLegacyDockerMapper=false)
    bool enableKubernetesApi{false};  // Query K8s API for pod metadata
    bool preferPodName{false};        // Return pod name instead of container name

    // Agent HTTP server (for receiving commands from DeepKernel server)
    int agentListenPort{8082};

    // Container filtering (regex pattern, empty = monitor all)
    std::string containerFilterRegex;

    // Policy settings
    std::string policyDir{"/var/lib/deepkernel/policies"};
    std::string policyEnforcementMode{"ERRNO"};  // ERRNO (block) or LOG (audit only)
};

AgentConfig loadConfig();

