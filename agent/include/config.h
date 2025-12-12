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

    // Docker integration
    std::string dockerSocketPath{"/var/run/docker.sock"};
    int containerMapCacheTTL{60};  // seconds

    // Agent HTTP server (for receiving commands from DeepKernel server)
    int agentListenPort{8082};

    // Container filtering (regex pattern, empty = monitor all)
    std::string containerFilterRegex;

    // Policy settings
    std::string policyDir{"/var/lib/deepkernel/policies"};
    std::string policyEnforcementMode{"ERRNO"};  // ERRNO (block) or LOG (audit only)
};

AgentConfig loadConfig();

