#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>

// Maps cgroup IDs to Docker container names.
// Uses Docker socket API to resolve container metadata.
class DockerMapper {
public:
    explicit DockerMapper(const std::string& dockerSocketPath = "/var/run/docker.sock",
                          int cacheTTLSeconds = 60);

    // Returns container name (e.g., "demo-backend") or empty string if not a Docker container.
    std::string getContainerName(uint64_t cgroupId, uint32_t pid);

    // Clear cache (useful for testing)
    void clearCache();

    // Check if a PID belongs to a Docker container
    bool isDockerContainer(uint32_t pid);

private:
    struct CacheEntry {
        std::string containerName;
        uint64_t expiresAtNs;
    };

    std::string dockerSocketPath_;
    int cacheTTLSeconds_;

    mutable std::mutex cacheMutex_;
    std::unordered_map<uint64_t, CacheEntry> cgroupToNameCache_;

    // Read /proc/<pid>/cgroup to extract Docker container hash
    std::string extractContainerHashFromCgroup(uint32_t pid);

    // Call Docker API to get container name from hash
    std::string queryDockerForName(const std::string& containerHash);

    // HTTP GET via Unix socket
    std::string httpGetUnixSocket(const std::string& socketPath, const std::string& path);

    // Get current monotonic time in nanoseconds
    uint64_t nowNs() const;
};

