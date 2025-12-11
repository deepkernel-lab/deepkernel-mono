#include "docker_mapper.h"

#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <regex>
#include <sstream>

#include <curl/curl.h>
#include <sys/un.h>

namespace {

// Callback for CURL to write response data
size_t writeCallback(void* contents, size_t size, size_t nmemb, std::string* output) {
    size_t totalSize = size * nmemb;
    output->append(static_cast<char*>(contents), totalSize);
    return totalSize;
}

// Simple JSON string extraction (avoid full JSON library dependency for now)
std::string extractJsonStringField(const std::string& json, const std::string& field) {
    // Look for "field":"value" or "field": "value"
    std::string pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"";
    std::regex re(pattern);
    std::smatch match;
    if (std::regex_search(json, match, re) && match.size() > 1) {
        return match[1].str();
    }
    return "";
}

}  // namespace

DockerMapper::DockerMapper(const std::string& dockerSocketPath, int cacheTTLSeconds)
    : dockerSocketPath_(dockerSocketPath), cacheTTLSeconds_(cacheTTLSeconds) {}

uint64_t DockerMapper::nowNs() const {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

void DockerMapper::clearCache() {
    std::lock_guard<std::mutex> lock(cacheMutex_);
    cgroupToNameCache_.clear();
}

std::string DockerMapper::getContainerName(uint64_t cgroupId, uint32_t pid) {
    // Check cache first
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        auto it = cgroupToNameCache_.find(cgroupId);
        if (it != cgroupToNameCache_.end()) {
            if (nowNs() < it->second.expiresAtNs) {
                return it->second.containerName;
            }
            // Cache expired, remove it
            cgroupToNameCache_.erase(it);
        }
    }

    // Extract container hash from cgroup path
    std::string containerHash = extractContainerHashFromCgroup(pid);
    if (containerHash.empty()) {
        // Not a Docker container, cache as empty
        std::lock_guard<std::mutex> lock(cacheMutex_);
        cgroupToNameCache_[cgroupId] = {"", nowNs() + cacheTTLSeconds_ * 1'000'000'000ULL};
        return "";
    }

    // Debug logging for demo troubleshooting
    static bool firstMapping = true;
    if (firstMapping) {
        std::cout << "DockerMapper: Found container hash " << containerHash << " from cgroup " << cgroupId << "\n";
        firstMapping = false;
    }

    // Query Docker API for container name
    std::string containerName = queryDockerForName(containerHash);

    // Log successful mapping for demo visibility
    if (!containerName.empty() && containerName != containerHash) {
        std::cout << "DockerMapper: Resolved cgroup " << cgroupId << " → " << containerName << "\n";
    }

    // Cache the result
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        cgroupToNameCache_[cgroupId] = {containerName, nowNs() + cacheTTLSeconds_ * 1'000'000'000ULL};
    }

    return containerName;
}

bool DockerMapper::isDockerContainer(uint32_t pid) {
    return !extractContainerHashFromCgroup(pid).empty();
}

std::string DockerMapper::extractContainerHashFromCgroup(uint32_t pid) {
    // Read /proc/<pid>/cgroup
    std::string cgroupPath = "/proc/" + std::to_string(pid) + "/cgroup";
    std::ifstream file(cgroupPath);
    if (!file.is_open()) {
        return "";
    }

    std::string line;
    while (std::getline(file, line)) {
        // Docker cgroup patterns (multiple patterns for compatibility):
        // cgroup v1: 0::/docker/<64-char-hash>
        // cgroup v2: 0::/system.slice/docker-<64-char-hash>.scope
        // Docker Compose: /docker/<hash>/docker-compose-<service>
        // Kubernetes: /kubepods/besteffort/pod<uid>/<hash>
        // Containerd: /system.slice/cri-containerd-<hash>.scope
        
        std::smatch match;

        // Pattern 1: docker/<hash> or docker-<hash>
        std::regex dockerPattern("docker[/-]([a-f0-9]{12,64})");
        if (std::regex_search(line, match, dockerPattern) && match.size() > 1) {
            std::string hash = match[1].str();
            return hash.length() > 12 ? hash.substr(0, 12) : hash;
        }

        // Pattern 2: containerd (cri-containerd-<hash>.scope)
        std::regex containerdPattern("cri-containerd-([a-f0-9]{12,64})");
        if (std::regex_search(line, match, containerdPattern) && match.size() > 1) {
            std::string hash = match[1].str();
            return hash.length() > 12 ? hash.substr(0, 12) : hash;
        }

        // Pattern 3: Kubernetes pods (kubepods/.../hash)
        std::regex kubePattern("kubepods.*?([a-f0-9]{64})");
        if (std::regex_search(line, match, kubePattern) && match.size() > 1) {
            std::string hash = match[1].str();
            return hash.substr(0, 12);  // Use short hash
        }

        // Pattern 4: Podman (libpod-<hash>.scope)
        std::regex podmanPattern("libpod-([a-f0-9]{12,64})");
        if (std::regex_search(line, match, podmanPattern) && match.size() > 1) {
            std::string hash = match[1].str();
            return hash.length() > 12 ? hash.substr(0, 12) : hash;
        }
    }

    return "";
}

std::string DockerMapper::queryDockerForName(const std::string& containerHash) {
    if (containerHash.empty()) {
        return "";
    }

    // Query Docker API: GET /containers/<hash>/json
    std::string path = "/containers/" + containerHash + "/json";
    std::string response = httpGetUnixSocket(dockerSocketPath_, path);

    if (response.empty()) {
        static bool warnedOnce = false;
        if (!warnedOnce) {
            std::cerr << "DockerMapper: Could not query Docker API for " << containerHash 
                      << " (is Docker socket accessible?)\n";
            warnedOnce = true;
        }
        return containerHash;  // Return hash as fallback
    }

    // Extract "Name" field from JSON response
    // Docker returns Name as "/container_name"
    std::string name = extractJsonStringField(response, "Name");
    if (!name.empty() && name[0] == '/') {
        name = name.substr(1);  // Remove leading slash
    }

    // If no name found, try to get from Labels (docker-compose uses this)
    if (name.empty()) {
        // Look for com.docker.compose.service label
        std::string serviceLabel = extractJsonStringField(response, "com.docker.compose.service");
        if (!serviceLabel.empty()) {
            name = serviceLabel;
        }
    }

    // Final fallback: try docker-compose project + service
    if (name.empty()) {
        std::string project = extractJsonStringField(response, "com.docker.compose.project");
        std::string service = extractJsonStringField(response, "com.docker.compose.service");
        if (!project.empty() && !service.empty()) {
            name = project + "-" + service;
        }
    }

    return name.empty() ? containerHash : name;
}

std::string DockerMapper::httpGetUnixSocket(const std::string& socketPath, const std::string& path) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return "";
    }

    std::string response;
    std::string url = "http://localhost" + path;

    curl_easy_setopt(curl, CURLOPT_UNIX_SOCKET_PATH, socketPath.c_str());
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 2L);  // 2 second timeout
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);

    CURLcode res = curl_easy_perform(curl);
    if (res != CURLE_OK) {
        // Silently fail - not all PIDs will be Docker containers
    }

    curl_easy_cleanup(curl);
    return response;
}

