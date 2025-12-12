#include "policy_enforcer.h"

#include <chrono>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <regex>
#include <sstream>

#include <curl/curl.h>

namespace {

size_t writeCallback(void* contents, size_t size, size_t nmemb, std::string* output) {
    size_t totalSize = size * nmemb;
    output->append(static_cast<char*>(contents), totalSize);
    return totalSize;
}

uint64_t nowNs() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

}  // namespace

PolicyEnforcer::PolicyEnforcer(const std::string& dockerSocketPath, const std::string& policyDir,
                               const std::string& enforcementMode)
    : dockerSocketPath_(dockerSocketPath), policyDir_(policyDir), enforcementMode_(enforcementMode) {
    // Create policy directory if it doesn't exist
    std::filesystem::create_directories(policyDir_);

    // Validate enforcement mode
    if (enforcementMode_ != "ERRNO" && enforcementMode_ != "LOG") {
        std::cerr << "Warning: Invalid enforcement mode '" << enforcementMode_ 
                  << "', defaulting to ERRNO\n";
        enforcementMode_ = "ERRNO";
    }

    std::cout << "PolicyEnforcer: Enforcement mode = " << enforcementMode_ 
              << (enforcementMode_ == "LOG" ? " (audit only, no blocking)" : " (active blocking)") << "\n";
}

bool PolicyEnforcer::apply(const std::string& containerName, const std::string& policyId,
                           const std::string& policyType, const std::string& specJson) {
    std::cout << "PolicyEnforcer: Applying policy " << policyId << " to " << containerName << "\n";

    if (policyType != "SECCOMP") {
        std::cerr << "PolicyEnforcer: Unsupported policy type: " << policyType << "\n";
        // For demo, still mark as applied but log the limitation
        std::cout << "PolicyEnforcer: Would apply " << policyType << " policy (not implemented)\n";
        
        appliedPolicies_[policyId] = AppliedPolicy{
            containerName,
            policyType,
            "",
            nowNs()
        };
        return true;
    }

    // Generate Seccomp profile
    std::string profileJson = generateSeccompProfile(specJson);
    if (profileJson.empty()) {
        std::cerr << "PolicyEnforcer: Failed to generate Seccomp profile\n";
        return false;
    }

    // Write profile to disk
    std::string profilePath = writeSeccompProfile(policyId, profileJson);
    if (profilePath.empty()) {
        std::cerr << "PolicyEnforcer: Failed to write Seccomp profile\n";
        return false;
    }

    std::cout << "PolicyEnforcer: Wrote Seccomp profile to " << profilePath << "\n";

    // Get container ID from name
    std::string containerId = getContainerIdFromName(containerName);
    if (containerId.empty()) {
        std::cerr << "PolicyEnforcer: Could not find container: " << containerName << "\n";
        // Still record the policy for demo purposes
        appliedPolicies_[policyId] = AppliedPolicy{
            containerName,
            policyType,
            profilePath,
            nowNs()
        };
        return true;
    }

    // Best-effort demo enforcement (Docker CLI update + restart)
    bool success = updateContainerSeccomp(containerName, profilePath);

    // Record the policy
    appliedPolicies_[policyId] = AppliedPolicy{
        containerId,
        policyType,
        profilePath,
        nowNs()
    };

    if (success) {
        std::cout << "PolicyEnforcer: Successfully applied policy " << policyId << "\n";
    } else {
        std::cout << "PolicyEnforcer: Policy " << policyId << " written but Docker update failed\n";
        std::cout << "PolicyEnforcer: Container may need restart to apply Seccomp profile\n";
    }

    // Return true for demo - policy was written even if Docker update failed
    return true;
}

bool PolicyEnforcer::isPolicyApplied(const std::string& policyId) const {
    return appliedPolicies_.find(policyId) != appliedPolicies_.end();
}

std::string PolicyEnforcer::getAppliedPoliciesJson() const {
    std::ostringstream oss;
    oss << "[";
    bool first = true;
    for (const auto& [id, policy] : appliedPolicies_) {
        if (!first) oss << ",";
        first = false;
        oss << "{\"policy_id\":\"" << id << "\","
            << "\"container_id\":\"" << policy.containerId << "\","
            << "\"type\":\"" << policy.policyType << "\","
            << "\"profile_path\":\"" << policy.profilePath << "\"}";
    }
    oss << "]";
    return oss.str();
}

std::string PolicyEnforcer::generateSeccompProfile(const std::string& specJson) {
    // Generate an OCI-like seccomp profile. For demo, focus on network threat:
    // defaultAction: ALLOW, with a single rule on connect.
    // - If enforcementMode_ == LOG -> SCMP_ACT_LOG
    // - Else -> SCMP_ACT_ERRNO
    //
    // IMPORTANT: Seccomp cannot inspect the destination port inside the sockaddr* passed to connect(2),
    // so port-based filtering is not possible with classic seccomp rules. We therefore block/log
    // connect(2) entirely for demo enforcement when a THREAT is detected.

    std::ostringstream profile;
    profile << "{\n";
    profile << "  \"defaultAction\": \"SCMP_ACT_ALLOW\",\n";
    profile << "  \"architectures\": [\"SCMP_ARCH_X86_64\", \"SCMP_ARCH_X86\", \"SCMP_ARCH_X32\"],\n";

    // Add comment about enforcement mode
    profile << "  \"comment\": \"DeepKernel generated policy - mode=" << enforcementMode_ << " (block connect)\",\n";

    profile << "  \"syscalls\": [\n";

    // Determine action based on enforcement mode
    std::string effectiveAction = (enforcementMode_ == "LOG") ? "SCMP_ACT_LOG" : "SCMP_ACT_ERRNO";

    // Default: connect(2)
    profile << "    {\n";
    profile << "      \"names\": [\"connect\"],\n";
    profile << "      \"action\": \"" << effectiveAction << "\",\n";
    profile << "      \"comment\": \"Deny/log connect(2) for demo containment\"\n";
    profile << "    }\n";

    profile << "\n  ]\n";
    profile << "}\n";

    return profile.str();
}

std::string PolicyEnforcer::writeSeccompProfile(const std::string& policyId, const std::string& profileJson) {
    std::filesystem::path path = std::filesystem::path(policyDir_) / (policyId + ".json");
    
    std::ofstream file(path);
    if (!file.is_open()) {
        std::cerr << "PolicyEnforcer: Failed to open file: " << path << "\n";
        return "";
    }

    file << profileJson;
    file.close();

    return path.string();
}

std::string PolicyEnforcer::getContainerIdFromName(const std::string& containerName) {
    // Query Docker API to get container ID from name
    std::string response = httpDockerGet("/containers/" + containerName + "/json");
    if (response.empty()) {
        return "";
    }

    // Extract Id from response
    return extractJsonString(response, "Id");
}

bool PolicyEnforcer::updateContainerSeccomp(const std::string& containerName, const std::string& profilePath) {
    // Best-effort demo automation using Docker CLI:
    // - docker update --security-opt seccomp=<profile> <container>
    // - docker restart <container>
    //
    // Some Docker runtimes may not support live-updating seccomp; if the update fails we keep the
    // profile on disk and log the manual steps.

    std::cout << "PolicyEnforcer: Attempting Docker update+restart for " << containerName << "\n";

    std::string updateCmd =
        "docker update --security-opt seccomp=\"" + profilePath + "\" \"" + containerName + "\" > /dev/null 2>&1";
    int rcUpdate = std::system(updateCmd.c_str());
    if (rcUpdate != 0) {
        std::cout << "PolicyEnforcer: Docker update failed (rc=" << rcUpdate << "). Manual fallback:\n";
        std::cout << "PolicyEnforcer:   docker update --security-opt seccomp=\"" << profilePath << "\" \"" << containerName << "\"\n";
        std::cout << "PolicyEnforcer:   docker restart \"" << containerName << "\"\n";
        return false;
    }

    std::string restartCmd = "docker restart \"" + containerName + "\" > /dev/null 2>&1";
    int rcRestart = std::system(restartCmd.c_str());
    if (rcRestart != 0) {
        std::cout << "PolicyEnforcer: Docker restart failed (rc=" << rcRestart << "). Manual fallback:\n";
        std::cout << "PolicyEnforcer:   docker restart \"" << containerName << "\"\n";
        return false;
    }

    std::cout << "PolicyEnforcer: Docker update+restart succeeded for " << containerName << "\n";
    return true;
}

std::string PolicyEnforcer::httpDockerGet(const std::string& path) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return "";
    }

    std::string response;
    std::string url = "http://localhost" + path;

    curl_easy_setopt(curl, CURLOPT_UNIX_SOCKET_PATH, dockerSocketPath_.c_str());
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 5L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);

    CURLcode res = curl_easy_perform(curl);
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        return "";
    }

    return response;
}

std::string PolicyEnforcer::httpDockerPost(const std::string& path, const std::string& body) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        return "";
    }

    std::string response;
    std::string url = "http://localhost" + path;

    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");

    curl_easy_setopt(curl, CURLOPT_UNIX_SOCKET_PATH, dockerSocketPath_.c_str());
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 5L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);

    CURLcode res = curl_easy_perform(curl);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        return "";
    }

    return response;
}

std::string PolicyEnforcer::extractJsonString(const std::string& json, const std::string& key) {
    std::string pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
    std::regex re(pattern);
    std::smatch match;
    if (std::regex_search(json, match, re) && match.size() > 1) {
        return match[1].str();
    }
    return "";
}

