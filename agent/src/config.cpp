#include "config.h"

#include <cstdlib>

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
}  // namespace

AgentConfig loadConfig() {
    AgentConfig cfg;
    cfg.agentId = envOrDefault("DK_AGENT_ID", cfg.agentId);
    cfg.serverUrl = envOrDefault("DK_SERVER_URL", cfg.serverUrl);
    cfg.nodeName = envOrDefault("DK_NODE_NAME", cfg.nodeName);
    cfg.shortWindowSec = envOrDefaultInt("DK_SHORT_WINDOW_SEC", cfg.shortWindowSec);
    cfg.sendIntervalSec = envOrDefaultInt("DK_SEND_INTERVAL_SEC", cfg.sendIntervalSec);
    cfg.minEventsPerWindow = envOrDefaultInt("DK_MIN_EVENTS_PER_WINDOW", cfg.minEventsPerWindow);
    cfg.longDumpDefaultDurationSec =
        envOrDefaultInt("DK_LONG_DUMP_DURATION_SEC", cfg.longDumpDefaultDurationSec);
    cfg.dumpDir = envOrDefault("DK_DUMP_DIR", cfg.dumpDir);
    cfg.syscallVocabSize = envOrDefaultInt("DK_SYSCALL_VOCAB_SIZE", cfg.syscallVocabSize);
    cfg.autoBaselineDump = envOrDefaultInt("DK_AUTO_BASELINE_DUMP", cfg.autoBaselineDump ? 1 : 0) != 0;
    return cfg;
}

