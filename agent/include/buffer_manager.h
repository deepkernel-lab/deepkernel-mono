#pragma once

#include <fstream>
#include <string>
#include <vector>

#include "syscall_event.h"

struct ContainerBuffer {
    std::string containerId;
    uint64_t lastTsNs{0};
    std::vector<SyscallEvent> currentShortWindow;
    bool isLongDumpActive{false};
    uint64_t longDumpStartTsNs{0};
    std::ofstream longDumpStream;
};

// TODO: add buffer management logic and serialization helpers.

