#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "event_types.h"

TraceRecord makeTraceRecord(const SyscallEvent& evt, uint64_t prevTsNs);

std::string buildWindowJson(const std::string& agentId,
                            const std::string& containerId,
                            uint64_t windowStartTsNs,
                            const std::vector<SyscallEvent>& events);

std::string buildDumpCompleteJson(const std::string& agentId,
                                  const std::string& containerId,
                                  const std::string& dumpPath,
                                  uint64_t startTsNs,
                                  int durationSec,
                                  uint64_t recordCount);

