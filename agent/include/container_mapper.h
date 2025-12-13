#pragma once

#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

/**
 * Container runtime detection and name resolution.
 * 
 * Supports multiple container runtimes:
 * - Docker (via /var/run/docker.sock)
 * - Containerd (via /run/containerd/containerd.sock or crictl)
 * - CRI-O (via /var/run/crio/crio.sock)
 * - Kubernetes (via downward API or API server)
 */
class ContainerMapper {
public:
    // Supported container runtimes
    enum class Runtime {
        UNKNOWN,
        DOCKER,
        CONTAINERD,
        CRIO,
    };

    // Container metadata
    struct ContainerInfo {
        std::string id;           // Short container ID (12 chars)
        std::string fullId;       // Full container ID (64 chars)
        std::string name;         // Container name
        std::string podName;      // Kubernetes pod name (if applicable)
        std::string podNamespace; // Kubernetes namespace (if applicable)
        std::string image;        // Container image
        Runtime runtime;          // Detected runtime
    };

    // Configuration
    struct Config {
        std::string dockerSocket = "/var/run/docker.sock";
        std::string containerdSocket = "/run/containerd/containerd.sock";
        std::string crioSocket = "/var/run/crio/crio.sock";
        std::string crictlPath = "/usr/bin/crictl";
        int cacheTTLSeconds = 60;
        bool enableKubernetesApi = true;  // Query K8s API for pod info
        bool preferPodName = true;        // Return pod name instead of container name
    };

    explicit ContainerMapper(const Config& config = Config{});

    /**
     * Get container name for a given cgroup ID and PID.
     * 
     * Returns the most descriptive name available:
     * - Kubernetes: "namespace/pod-name" or "pod-name"
     * - Docker Compose: "project_service_1"
     * - Plain Docker: "container-name"
     * - Fallback: Short container ID
     */
    std::string getContainerName(uint64_t cgroupId, uint32_t pid);

    /**
     * Get full container metadata.
     */
    std::optional<ContainerInfo> getContainerInfo(uint64_t cgroupId, uint32_t pid);

    /**
     * Detect which container runtime is running.
     */
    Runtime detectRuntime();

    /**
     * Check if PID belongs to any container.
     */
    bool isContainer(uint32_t pid);

    /**
     * Clear the name cache.
     */
    void clearCache();

private:
    Config config_;
    Runtime detectedRuntime_ = Runtime::UNKNOWN;

    struct CacheEntry {
        ContainerInfo info;
        uint64_t expiresAtNs;
    };

    mutable std::mutex cacheMutex_;
    std::unordered_map<uint64_t, CacheEntry> cache_;

    // Cgroup parsing - extract container ID from /proc/<pid>/cgroup
    struct CgroupInfo {
        std::string containerId;
        std::string podUid;      // Kubernetes pod UID if present
        Runtime runtime;
    };
    CgroupInfo parseCgroup(uint32_t pid);

    // Runtime-specific name resolution
    std::optional<ContainerInfo> queryDocker(const std::string& containerId);
    std::optional<ContainerInfo> queryContainerd(const std::string& containerId);
    std::optional<ContainerInfo> queryCrio(const std::string& containerId);
    std::optional<ContainerInfo> queryKubernetes(const std::string& podUid);

    // Helper: Query via crictl (works with containerd and CRI-O)
    std::optional<ContainerInfo> queryCrictl(const std::string& containerId);

    // Helper: Read Kubernetes pod info from downward API
    std::optional<ContainerInfo> readKubernetesDownwardApi();

    // HTTP helpers
    std::string httpGetUnixSocket(const std::string& socketPath, const std::string& path);
    std::string execCommand(const std::string& cmd);

    // Time
    uint64_t nowNs() const;

    // JSON parsing helpers
    std::string extractJsonField(const std::string& json, const std::string& field);
};

