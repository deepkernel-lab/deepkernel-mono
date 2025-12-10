#pragma once

#include <fstream>
#include <string>
#include <vector>

#include "event_types.h"

struct ContainerBuffer {
    std::string containerId;
    uint64_t windowStartTsNs{0};
    uint64_t lastEventTsNs{0};
    std::vector<SyscallEvent> currentShortWindow;

    // Long dump state
    bool isLongDumpActive{false};
    uint64_t longDumpStartTsNs{0};
    uint64_t longDumpEndTsNs{0};
    uint64_t lastDumpTsNs{0};
    std::ofstream longDumpStream;
    std::string longDumpFilePath;       // Full path to dump file
    uint64_t longDumpRecordCount{0};    // Number of records written
    int longDumpDurationSec{0};         // Requested duration
};

// Buffer management helpers implemented in agent.cpp.

