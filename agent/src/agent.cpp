#include "agent.h"

#include <chrono>
#include <csignal>
#include <cerrno>
#include <cstdio>
#include <ctime>
#include <filesystem>
#include <iostream>
#include <sstream>

#include <bpf/bpf.h>

#include "serialization.h"

namespace {
constexpr const char* kBpfObjFile = "deepkernel.bpf.o";
constexpr uint32_t kTraceVersion = 1;

std::atomic<bool> gSignalStop{false};

void handleSignal(int) { gSignalStop = true; }

uint64_t monotonicNowNs() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

std::string nowTimestampForFile() {
    auto now = std::chrono::system_clock::now();
    auto t = std::chrono::system_clock::to_time_t(now);
    char buf[32];
    std::strftime(buf, sizeof(buf), "%Y%m%d-%H%M%S", std::localtime(&t));
    return std::string(buf);
}

uint64_t nsToUs(uint64_t ns) { return ns / 1000ULL; }

std::string sanitizeContainerId(const std::string& id) {
    std::string out = id;
    for (auto& ch : out) {
        if (ch == '/' || ch == '\\') {
            ch = '_';
        }
    }
    return out;
}
}  // namespace

Agent::Agent(AgentConfig config) : config_(std::move(config)) {}

Agent::~Agent() { cleanupBpf(); }

bool Agent::initBpf() {
    bpf_object* obj = bpf_object__open_file(kBpfObjFile, nullptr);
    if (!obj) {
        std::cerr << "Failed to open BPF object: " << kBpfObjFile << "\n";
        return false;
    }
    if (bpf_object__load(obj)) {
        std::cerr << "Failed to load BPF object\n";
        return false;
    }

    struct bpf_program* prog = bpf_object__find_program_by_title(obj, "tracepoint/syscalls/sys_enter");
    if (!prog) {
        std::cerr << "Failed to find BPF program\n";
        return false;
    }

    bpf_link* link = bpf_program__attach_tracepoint(prog, "syscalls", "sys_enter");
    if (!link) {
        std::cerr << "Failed to attach tracepoint\n";
        return false;
    }

    tp_link_.reset(link);

    int map_fd = bpf_object__find_map_fd_by_name(obj, "events");
    if (map_fd < 0) {
        std::cerr << "Failed to find events map\n";
        return false;
    }

    ring_buffer* rb = ring_buffer__new(map_fd, &Agent::handleEventThunk, this, nullptr);
    if (!rb) {
        std::cerr << "Failed to create ring buffer\n";
        return false;
    }

    ringbuf_.reset(rb);
    bpf_fd_ = map_fd;
    bpf_obj_.reset(obj);
    return true;
}

void Agent::cleanupBpf() {
    ringbuf_.reset();
    tp_link_.reset();
    bpf_obj_.reset();
}

int Agent::handleEventThunk(void* ctx, void* data, size_t len) {
    if (len < sizeof(KernelSyscallEvent)) {
        return 0;
    }
    Agent* self = static_cast<Agent*>(ctx);
    return self->handleEvent(*reinterpret_cast<KernelSyscallEvent*>(data));
}

int Agent::handleEvent(const KernelSyscallEvent& evt) {
    SyscallEvent mapped{
        .tsNs = evt.ts_ns,
        .pid = evt.pid,
        .tid = evt.tid,
        .cgroupId = evt.cgroup_id,
        .syscallId = evt.syscall_id,
        .argClass = evt.arg_class,
        .argBucket = evt.arg_bucket,
    };

    std::string containerId = mapContainerId(mapped.cgroupId);
    auto& buffer = buffers_[containerId];
    buffer.containerId = containerId;

    if (buffer.currentShortWindow.empty()) {
        buffer.windowStartTsNs = mapped.tsNs;
    }
    buffer.lastEventTsNs = mapped.tsNs;
    buffer.currentShortWindow.push_back(mapped);

    appendLongDump(buffer, mapped);

    uint64_t windowDuration = mapped.tsNs - buffer.windowStartTsNs;
    if (windowDuration >= static_cast<uint64_t>(config_.shortWindowSec) * 1'000'000'000ULL &&
        static_cast<int>(buffer.currentShortWindow.size()) >= config_.minEventsPerWindow) {
        processShortWindow(buffer);
    }

    stopExpiredLongDumps(mapped.tsNs);
    return 0;
}

void Agent::appendLongDump(ContainerBuffer& buffer, const SyscallEvent& evt) {
    if (!buffer.isLongDumpActive || !buffer.longDumpStream.is_open()) {
        return;
    }
    if (evt.tsNs >= buffer.longDumpEndTsNs) {
        buffer.longDumpStream.close();
        buffer.isLongDumpActive = false;
        return;
    }

    uint64_t prev = buffer.lastDumpTsNs == 0 ? buffer.longDumpStartTsNs : buffer.lastDumpTsNs;
    TraceRecord rec = makeTraceRecord(evt, prev);
    buffer.longDumpStream.write(reinterpret_cast<const char*>(&rec), sizeof(rec));
    buffer.lastDumpTsNs = evt.tsNs;
}

void Agent::processShortWindow(ContainerBuffer& buffer) {
    if (buffer.currentShortWindow.empty()) {
        return;
    }

    std::string url = config_.serverUrl + "/api/v1/agent/windows";
    std::string payload = buildWindowJson(config_.agentId, buffer.containerId, buffer.windowStartTsNs, buffer.currentShortWindow);
    if (!http_.postJson(url, payload)) {
        std::cerr << "Failed to POST window for container " << buffer.containerId << "\n";
    }

    // Reset window
    buffer.currentShortWindow.clear();
    buffer.windowStartTsNs = 0;
}

std::string Agent::mapContainerId(uint64_t cgroupId) const {
    // Placeholder mapping: use cgroup_id string. This should be replaced with
    // a lookup against cgroup/container metadata for real deployments.
    return std::to_string(cgroupId);
}

void Agent::requestLongDump(const std::string& containerId, int durationSec) {
    auto& buffer = buffers_[containerId];
    buffer.containerId = containerId;

    namespace fs = std::filesystem;
    fs::create_directories(config_.dumpDir);

    std::string safeId = sanitizeContainerId(containerId);
    std::string fileName = safeId + "-" + nowTimestampForFile() + ".dkdump";
    fs::path filePath = fs::path(config_.dumpDir) / fileName;
    buffer.longDumpStream.open(filePath, std::ios::binary | std::ios::trunc);
    if (!buffer.longDumpStream.is_open()) {
        std::cerr << "Failed to open dump file: " << filePath << "\n";
        return;
    }

    buffer.longDumpStartTsNs = buffer.lastEventTsNs ? buffer.lastEventTsNs : monotonicNowNs();
    buffer.longDumpEndTsNs =
        buffer.longDumpStartTsNs + static_cast<uint64_t>(durationSec) * 1'000'000'000ULL;
    buffer.lastDumpTsNs = 0;
    buffer.isLongDumpActive = true;

    TraceHeader header{};
    header.version = kTraceVersion;
    header.syscall_vocab_size = static_cast<uint32_t>(config_.syscallVocabSize);
    std::snprintf(header.container_id, sizeof(header.container_id), "%s", containerId.c_str());
    header.start_ts_ns = buffer.longDumpStartTsNs;
    buffer.longDumpStream.write(reinterpret_cast<const char*>(&header), sizeof(header));
}

void Agent::stopExpiredLongDumps(uint64_t nowNs) {
    for (auto& [id, buf] : buffers_) {
        if (buf.isLongDumpActive && nowNs >= buf.longDumpEndTsNs) {
            if (buf.longDumpStream.is_open()) {
                buf.longDumpStream.close();
            }
            buf.isLongDumpActive = false;
        }
    }
}

int Agent::run() {
    if (!initBpf()) {
        return 1;
    }

    std::signal(SIGINT, handleSignal);
    std::signal(SIGTERM, handleSignal);

    std::cout << "DeepKernel agent started. Posting windows to " << config_.serverUrl << "\n";

    // Optional: start baseline long dump for all observed containers (off by default).
    if (config_.autoBaselineDump) {
        // Start a generic dump using a placeholder container id; real system would iterate known targets.
        requestLongDump("baseline", config_.longDumpDefaultDurationSec);
    }

    while (!gSignalStop.load()) {
        int err = ring_buffer__poll(ringbuf_.get(), 100 /* ms */);
        if (err == -EINTR) {
            break;
        }
        if (err < 0) {
            std::cerr << "ring_buffer__poll error: " << err << "\n";
            break;
        }
    }

    cleanupBpf();
    return 0;
}

