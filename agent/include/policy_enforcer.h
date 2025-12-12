#pragma once

#include <string>
#include <unordered_map>
#include <cstdint>

// Policy enforcement via Docker API.
// Supports SECCOMP policy application.
class PolicyEnforcer {
public:
    explicit PolicyEnforcer(const std::string& dockerSocketPath = "/var/run/docker.sock",
                            const std::string& policyDir = "/var/lib/deepkernel/policies",
                            const std::string& enforcementMode = "ERRNO");

    // Apply a policy to a container
    // Returns true if successful
    bool apply(const std::string& containerName, const std::string& policyId,
               const std::string& policyType, const std::string& specJson);

    // Check if a policy has been applied
    bool isPolicyApplied(const std::string& policyId) const;

    // Get status of applied policies
    std::string getAppliedPoliciesJson() const;

private:
    std::string dockerSocketPath_;
    std::string policyDir_;
    std::string enforcementMode_;  // "ERRNO" (block) or "LOG" (audit)

    struct AppliedPolicy {
        std::string containerId;
        std::string policyType;
        std::string profilePath;
        uint64_t appliedAtNs;
    };

    std::unordered_map<std::string, AppliedPolicy> appliedPolicies_;

    // Generate OCI-compliant Seccomp profile JSON
    std::string generateSeccompProfile(const std::string& specJson);

    // Write Seccomp profile to disk
    std::string writeSeccompProfile(const std::string& policyId, const std::string& profileJson);

    // Get container ID (hash) from name
    std::string getContainerIdFromName(const std::string& containerName);

    // Best-effort demo enforcement (Docker CLI update + restart)
    bool updateContainerSeccomp(const std::string& containerName, const std::string& profilePath);

    // HTTP communication with Docker socket
    std::string httpDockerGet(const std::string& path);
    std::string httpDockerPost(const std::string& path, const std::string& body);

    // JSON helpers
    std::string extractJsonString(const std::string& json, const std::string& key);
};

