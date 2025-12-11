#include <gtest/gtest.h>
#include <filesystem>
#include <fstream>

#include "policy_enforcer.h"

namespace fs = std::filesystem;

class PolicyEnforcerTest : public ::testing::Test {
protected:
    void SetUp() override {
        testPolicyDir_ = "/tmp/deepkernel-test-policies-" + std::to_string(getpid());
        fs::create_directories(testPolicyDir_);
    }

    void TearDown() override {
        fs::remove_all(testPolicyDir_);
    }

    std::string testPolicyDir_;
};

TEST_F(PolicyEnforcerTest, ConstructorCreatesDirectory) {
    std::string newDir = testPolicyDir_ + "/subdir";
    PolicyEnforcer enforcer("/nonexistent/docker.sock", newDir);
    EXPECT_TRUE(fs::exists(newDir));
}

TEST_F(PolicyEnforcerTest, ApplySeccompPolicy) {
    PolicyEnforcer enforcer("/nonexistent/docker.sock", testPolicyDir_);
    
    std::string specJson = R"({
        "type": "SECCOMP",
        "syscalls": [
            {"name": "connect", "action": "SCMP_ACT_ERRNO"}
        ]
    })";
    
    bool result = enforcer.apply("test-container", "policy-001", "SECCOMP", specJson);
    
    // Should succeed (even if Docker is not available, profile is written)
    EXPECT_TRUE(result);
    EXPECT_TRUE(enforcer.isPolicyApplied("policy-001"));
}

TEST_F(PolicyEnforcerTest, PolicyFileWritten) {
    PolicyEnforcer enforcer("/nonexistent/docker.sock", testPolicyDir_);
    
    std::string specJson = R"({
        "type": "SECCOMP",
        "syscalls": [{"name": "connect", "action": "SCMP_ACT_ERRNO"}]
    })";
    
    enforcer.apply("test-container", "policy-002", "SECCOMP", specJson);
    
    // Check that policy file was created
    fs::path policyFile = fs::path(testPolicyDir_) / "policy-002.json";
    EXPECT_TRUE(fs::exists(policyFile));
    
    // Check file contents
    std::ifstream ifs(policyFile);
    std::string contents((std::istreambuf_iterator<char>(ifs)),
                         std::istreambuf_iterator<char>());
    EXPECT_NE(contents.find("SCMP_ACT_ALLOW"), std::string::npos);
    EXPECT_NE(contents.find("connect"), std::string::npos);
}

TEST_F(PolicyEnforcerTest, UnsupportedPolicyType) {
    PolicyEnforcer enforcer("/nonexistent/docker.sock", testPolicyDir_);
    
    // APPARMOR is not yet supported but should still be recorded
    bool result = enforcer.apply("test-container", "policy-003", "APPARMOR", "{}");
    
    EXPECT_TRUE(result);  // Returns true for demo purposes
    EXPECT_TRUE(enforcer.isPolicyApplied("policy-003"));
}

TEST_F(PolicyEnforcerTest, GetAppliedPoliciesJson) {
    PolicyEnforcer enforcer("/nonexistent/docker.sock", testPolicyDir_);
    
    enforcer.apply("container-1", "policy-a", "SECCOMP", "{}");
    enforcer.apply("container-2", "policy-b", "SECCOMP", "{}");
    
    std::string json = enforcer.getAppliedPoliciesJson();
    
    EXPECT_NE(json.find("policy-a"), std::string::npos);
    EXPECT_NE(json.find("policy-b"), std::string::npos);
    EXPECT_NE(json.find("container-1"), std::string::npos);
    EXPECT_NE(json.find("container-2"), std::string::npos);
}

TEST_F(PolicyEnforcerTest, IsPolicyAppliedReturnsFalseForUnknown) {
    PolicyEnforcer enforcer("/nonexistent/docker.sock", testPolicyDir_);
    
    EXPECT_FALSE(enforcer.isPolicyApplied("nonexistent-policy"));
}

