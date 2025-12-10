#pragma once

#include <string>

struct AgentConfig {
    std::string agentId{"node-1"};
    std::string serverUrl{"http://localhost:8080"};
    std::string nodeName{"worker-01"};
    int shortWindowSec{5};
    int sendIntervalSec{5};
    int minEventsPerWindow{20};
    int longDumpDefaultDurationSec{1200};
    std::string dumpDir{"/var/lib/deepkernel/dumps"};
    int syscallVocabSize{256};
    bool autoBaselineDump{false};
};

AgentConfig loadConfig();

