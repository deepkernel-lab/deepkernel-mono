#include "container_mapper.h"

#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <regex>
#include <sstream>
#include <sys/stat.h>

#include <curl/curl.h>

namespace {

size_t writeCallback(void* contents, size_t size, size_t nmemb, std::string* output) {
    size_t totalSize = size * nmemb;
    output->append(static_cast<char*>(contents), totalSize);
    return totalSize;
}

bool fileExists(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

}  // namespace

ContainerMapper::ContainerMapper(const Config& config) : config_(config) {
    detectedRuntime_ = detectRuntime();
    
    std::cout << "ContainerMapper initialized, detected runtime: ";
    switch (detectedRuntime_) {
        case Runtime::DOCKER:     std::cout << "Docker\n"; break;
        case Runtime::CONTAINERD: std::cout << "containerd\n"; break;
        case Runtime::CRIO:       std::cout << "CRI-O\n"; break;
        default:                  std::cout << "unknown\n"; break;
    }
}

ContainerMapper::Runtime ContainerMapper::detectRuntime() {
    // Check for sockets in order of preference
    if (fileExists(config_.dockerSocket)) {
        return Runtime::DOCKER;
    }
    if (fileExists(config_.containerdSocket)) {
        return Runtime::CONTAINERD;
    }
    if (fileExists(config_.crioSocket)) {
        return Runtime::CRIO;
    }
    
    // Check if crictl is available (can work with containerd or CRI-O)
    if (fileExists(config_.crictlPath)) {
        // Try to determine which CRI backend is being used
        std::string result = execCommand(config_.crictlPath + " info 2>/dev/null | head -1");
        if (!result.empty()) {
            if (result.find("containerd") != std::string::npos) {
                return Runtime::CONTAINERD;
            }
            if (result.find("cri-o") != std::string::npos) {
                return Runtime::CRIO;
            }
        }
    }
    
    return Runtime::UNKNOWN;
}

uint64_t ContainerMapper::nowNs() const {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

void ContainerMapper::clearCache() {
    std::lock_guard<std::mutex> lock(cacheMutex_);
    cache_.clear();
}

std::string ContainerMapper::getContainerName(uint64_t cgroupId, uint32_t pid) {
    auto info = getContainerInfo(cgroupId, pid);
    if (!info) {
        return "";
    }
    
    // Prefer pod name for Kubernetes environments
    if (config_.preferPodName && !info->podName.empty()) {
        if (!info->podNamespace.empty() && info->podNamespace != "default") {
            return info->podNamespace + "/" + info->podName;
        }
        return info->podName;
    }
    
    // Fall back to container name
    if (!info->name.empty()) {
        return info->name;
    }
    
    // Last resort: short ID
    return info->id;
}

std::optional<ContainerMapper::ContainerInfo> ContainerMapper::getContainerInfo(
    uint64_t cgroupId, uint32_t pid) {
    
    // Check cache
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        auto it = cache_.find(cgroupId);
        if (it != cache_.end()) {
            if (nowNs() < it->second.expiresAtNs) {
                return it->second.info;
            }
            cache_.erase(it);
        }
    }
    
    // Parse cgroup to get container ID
    CgroupInfo cgroup = parseCgroup(pid);
    if (cgroup.containerId.empty()) {
        return std::nullopt;
    }
    
    // Query runtime for container info
    std::optional<ContainerInfo> info;
    
    switch (cgroup.runtime) {
        case Runtime::DOCKER:
            info = queryDocker(cgroup.containerId);
            break;
        case Runtime::CONTAINERD:
        case Runtime::CRIO:
            // Use crictl for both containerd and CRI-O
            info = queryCrictl(cgroup.containerId);
            break;
        default:
            // Try all methods
            info = queryDocker(cgroup.containerId);
            if (!info) {
                info = queryCrictl(cgroup.containerId);
            }
            break;
    }
    
    // Try Kubernetes API for pod info
    if (info && config_.enableKubernetesApi && !cgroup.podUid.empty()) {
        auto k8sInfo = queryKubernetes(cgroup.podUid);
        if (k8sInfo) {
            info->podName = k8sInfo->podName;
            info->podNamespace = k8sInfo->podNamespace;
        }
    }
    
    if (!info) {
        // Return basic info with just the ID
        ContainerInfo basicInfo;
        basicInfo.id = cgroup.containerId.substr(0, 12);
        basicInfo.fullId = cgroup.containerId;
        basicInfo.name = cgroup.containerId.substr(0, 12);
        basicInfo.podName = "";
        basicInfo.podNamespace = "";
        basicInfo.image = "";
        basicInfo.runtime = cgroup.runtime;
        info = basicInfo;
    }
    
    // Cache result
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        cache_[cgroupId] = {*info, nowNs() + config_.cacheTTLSeconds * 1'000'000'000ULL};
    }
    
    return info;
}

bool ContainerMapper::isContainer(uint32_t pid) {
    CgroupInfo cgroup = parseCgroup(pid);
    return !cgroup.containerId.empty();
}

ContainerMapper::CgroupInfo ContainerMapper::parseCgroup(uint32_t pid) {
    CgroupInfo result;
    result.runtime = Runtime::UNKNOWN;
    
    std::string cgroupPath = "/proc/" + std::to_string(pid) + "/cgroup";
    std::ifstream file(cgroupPath);
    if (!file.is_open()) {
        return result;
    }
    
    std::string line;
    while (std::getline(file, line)) {
        std::smatch match;
        
        // Docker: docker/<hash> or docker-<hash>.scope
        std::regex dockerPattern("docker[/-]([a-f0-9]{12,64})");
        if (std::regex_search(line, match, dockerPattern) && match.size() > 1) {
            result.containerId = match[1].str();
            result.runtime = Runtime::DOCKER;
            return result;
        }
        
        // Containerd: cri-containerd-<hash>.scope
        std::regex containerdPattern("cri-containerd-([a-f0-9]{12,64})");
        if (std::regex_search(line, match, containerdPattern) && match.size() > 1) {
            result.containerId = match[1].str();
            result.runtime = Runtime::CONTAINERD;
            
            // Extract pod UID if present
            std::regex podPattern("kubepods[^/]*/[^/]*/pod([a-f0-9-]{36})");
            if (std::regex_search(line, match, podPattern) && match.size() > 1) {
                result.podUid = match[1].str();
            }
            return result;
        }
        
        // CRI-O: crio-<hash>.scope
        std::regex crioPattern("crio-([a-f0-9]{12,64})");
        if (std::regex_search(line, match, crioPattern) && match.size() > 1) {
            result.containerId = match[1].str();
            result.runtime = Runtime::CRIO;
            
            // Extract pod UID
            std::regex podPattern("kubepods[^/]*/[^/]*/pod([a-f0-9-]{36})");
            if (std::regex_search(line, match, podPattern) && match.size() > 1) {
                result.podUid = match[1].str();
            }
            return result;
        }
        
        // Generic Kubernetes pattern (various runtimes)
        // Format: /kubepods/<qos>/pod<uid>/<container-id>
        std::regex kubePattern("kubepods[^/]*/[^/]*/pod([a-f0-9-]{36})/([a-f0-9]{64})");
        if (std::regex_search(line, match, kubePattern) && match.size() > 2) {
            result.podUid = match[1].str();
            result.containerId = match[2].str();
            result.runtime = detectedRuntime_;  // Use detected runtime
            return result;
        }
        
        // Podman: libpod-<hash>.scope
        std::regex podmanPattern("libpod-([a-f0-9]{12,64})");
        if (std::regex_search(line, match, podmanPattern) && match.size() > 1) {
            result.containerId = match[1].str();
            result.runtime = Runtime::UNKNOWN;  // Treat as unknown (podman is docker-compatible)
            return result;
        }
    }
    
    return result;
}

std::optional<ContainerMapper::ContainerInfo> ContainerMapper::queryDocker(
    const std::string& containerId) {
    
    std::string shortId = containerId.length() > 12 ? containerId.substr(0, 12) : containerId;
    std::string path = "/containers/" + shortId + "/json";
    std::string response = httpGetUnixSocket(config_.dockerSocket, path);
    
    if (response.empty()) {
        return std::nullopt;
    }
    
    ContainerInfo info;
    info.id = shortId;
    info.fullId = containerId;
    info.runtime = Runtime::DOCKER;
    
    // Extract Name (Docker returns "/name")
    std::string name = extractJsonField(response, "Name");
    if (!name.empty() && name[0] == '/') {
        name = name.substr(1);
    }
    info.name = name;
    
    // Extract Image
    info.image = extractJsonField(response, "Image");
    
    // Check for Kubernetes labels (if Docker is used as K8s runtime)
    std::string podName = extractJsonField(response, "io.kubernetes.pod.name");
    std::string podNamespace = extractJsonField(response, "io.kubernetes.pod.namespace");
    info.podName = podName;
    info.podNamespace = podNamespace;
    
    // Check for Docker Compose service name
    if (info.name.empty()) {
        std::string service = extractJsonField(response, "com.docker.compose.service");
        std::string project = extractJsonField(response, "com.docker.compose.project");
        if (!project.empty() && !service.empty()) {
            info.name = project + "_" + service;
        } else if (!service.empty()) {
            info.name = service;
        }
    }
    
    return info;
}

std::optional<ContainerMapper::ContainerInfo> ContainerMapper::queryCrictl(
    const std::string& containerId) {
    
    if (!fileExists(config_.crictlPath)) {
        return std::nullopt;
    }
    
    std::string shortId = containerId.length() > 12 ? containerId.substr(0, 12) : containerId;
    
    // Query container info via crictl
    std::string cmd = config_.crictlPath + " inspect " + shortId + " 2>/dev/null";
    std::string output = execCommand(cmd);
    
    if (output.empty()) {
        return std::nullopt;
    }
    
    ContainerInfo info;
    info.id = shortId;
    info.fullId = containerId;
    info.runtime = detectedRuntime_;
    
    // Parse crictl JSON output
    // Container name is in metadata.name
    std::regex namePattern("\"name\"\\s*:\\s*\"([^\"]+)\"");
    std::smatch match;
    if (std::regex_search(output, match, namePattern) && match.size() > 1) {
        info.name = match[1].str();
    }
    
    // Image
    std::regex imagePattern("\"image\"\\s*:\\s*\"([^\"]+)\"");
    if (std::regex_search(output, match, imagePattern) && match.size() > 1) {
        info.image = match[1].str();
    }
    
    // Kubernetes labels
    std::regex podNamePattern("\"io\\.kubernetes\\.pod\\.name\"\\s*:\\s*\"([^\"]+)\"");
    if (std::regex_search(output, match, podNamePattern) && match.size() > 1) {
        info.podName = match[1].str();
    }
    
    std::regex podNsPattern("\"io\\.kubernetes\\.pod\\.namespace\"\\s*:\\s*\"([^\"]+)\"");
    if (std::regex_search(output, match, podNsPattern) && match.size() > 1) {
        info.podNamespace = match[1].str();
    }
    
    return info;
}

std::optional<ContainerMapper::ContainerInfo> ContainerMapper::queryContainerd(
    const std::string& containerId) {
    // Containerd doesn't have a simple HTTP API like Docker
    // Use crictl instead (which works with containerd's CRI socket)
    return queryCrictl(containerId);
}

std::optional<ContainerMapper::ContainerInfo> ContainerMapper::queryCrio(
    const std::string& containerId) {
    // CRI-O also uses CRI, so use crictl
    return queryCrictl(containerId);
}

std::optional<ContainerMapper::ContainerInfo> ContainerMapper::queryKubernetes(
    const std::string& podUid) {
    
    // First try Kubernetes downward API (environment variables or mounted files)
    // This is populated when running inside a Kubernetes pod
    
    // Check for pod info file (mounted via downward API)
    std::string podInfoPath = "/etc/podinfo/";
    if (fileExists(podInfoPath + "name")) {
        ContainerInfo info;
        
        std::ifstream nameFile(podInfoPath + "name");
        if (nameFile.is_open()) {
            std::getline(nameFile, info.podName);
        }
        
        std::ifstream nsFile(podInfoPath + "namespace");
        if (nsFile.is_open()) {
            std::getline(nsFile, info.podNamespace);
        }
        
        if (!info.podName.empty()) {
            return info;
        }
    }
    
    // Try environment variables (if agent runs in a pod)
    const char* podName = std::getenv("KUBERNETES_POD_NAME");
    const char* podNamespace = std::getenv("KUBERNETES_NAMESPACE");
    if (podName) {
        ContainerInfo info;
        info.podName = podName;
        info.podNamespace = podNamespace ? podNamespace : "default";
        return info;
    }
    
    // Query Kubernetes API server (requires service account token)
    std::string tokenPath = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    std::string caPath = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
    
    if (!fileExists(tokenPath)) {
        return std::nullopt;
    }
    
    // Read service account token
    std::ifstream tokenFile(tokenPath);
    if (!tokenFile.is_open()) {
        return std::nullopt;
    }
    std::string token((std::istreambuf_iterator<char>(tokenFile)),
                       std::istreambuf_iterator<char>());
    
    // Query K8s API for pod by UID
    // Note: This requires list pods permission, which might not be available
    // A better approach is to use downward API or node-level kubelet API
    
    // For now, just return empty - the crictl query should have the info
    return std::nullopt;
}

std::string ContainerMapper::httpGetUnixSocket(const std::string& socketPath,
                                                const std::string& path) {
    if (!fileExists(socketPath)) {
        return "";
    }
    
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
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 2L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    
    CURLcode res = curl_easy_perform(curl);
    curl_easy_cleanup(curl);
    
    if (res != CURLE_OK) {
        return "";
    }
    
    return response;
}

std::string ContainerMapper::execCommand(const std::string& cmd) {
    std::array<char, 4096> buffer;
    std::string result;
    
    // Use lambda deleter to avoid GCC warning about function attributes
    auto pipeDeleter = [](FILE* f) { if (f) pclose(f); };
    std::unique_ptr<FILE, decltype(pipeDeleter)> pipe(popen(cmd.c_str(), "r"), pipeDeleter);
    if (!pipe) {
        return "";
    }
    
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    
    return result;
}

std::string ContainerMapper::extractJsonField(const std::string& json, const std::string& field) {
    std::string pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"";
    std::regex re(pattern);
    std::smatch match;
    if (std::regex_search(json, match, re) && match.size() > 1) {
        return match[1].str();
    }
    return "";
}

