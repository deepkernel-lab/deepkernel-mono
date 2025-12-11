#include "serialization.h"

#include <sstream>

namespace {
uint64_t nsToUs(uint64_t ns) { return ns / 1000ULL; }
}

TraceRecord makeTraceRecord(const SyscallEvent& evt, uint64_t prevTsNs) {
    TraceRecord rec{
        .delta_ts_us = static_cast<uint32_t>(nsToUs(evt.tsNs - prevTsNs)),
        .syscall_id = evt.syscallId,
        .arg_class = evt.argClass,
        .arg_bucket = evt.argBucket,
    };
    return rec;
}

std::string buildWindowJson(const std::string& agentId,
                            const std::string& containerId,
                            uint64_t windowStartTsNs,
                            const std::vector<SyscallEvent>& events) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"version\":1,";
    oss << "\"agent_id\":\"" << agentId << "\",";
    oss << "\"container_id\":\"" << containerId << "\",";
    oss << "\"window_start_ts_ns\":" << windowStartTsNs << ",";
    oss << "\"records\":[";
    bool first = true;
    for (const auto& ev : events) {
        if (!first) {
            oss << ",";
        }
        first = false;
        uint64_t deltaUs = nsToUs(ev.tsNs - windowStartTsNs);
        oss << "{"
            << "\"delta_ts_us\":" << deltaUs << ","
            << "\"syscall_id\":" << ev.syscallId << ","
            << "\"arg_class\":" << static_cast<int>(ev.argClass) << ","
            << "\"arg_bucket\":" << static_cast<int>(ev.argBucket)
            << "}";
    }
    oss << "]";
    oss << "}";
    return oss.str();
}

std::string buildDumpCompleteJson(const std::string& agentId,
                                  const std::string& containerId,
                                  const std::string& dumpPath,
                                  uint64_t startTsNs,
                                  int durationSec,
                                  uint64_t recordCount) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"agent_id\":\"" << agentId << "\",";
    oss << "\"container_id\":\"" << containerId << "\",";
    oss << "\"dump_path\":\"" << dumpPath << "\",";
    oss << "\"start_ts_ns\":" << startTsNs << ",";
    oss << "\"duration_sec\":" << durationSec << ",";
    oss << "\"record_count\":" << recordCount;
    oss << "}";
    return oss.str();
}

