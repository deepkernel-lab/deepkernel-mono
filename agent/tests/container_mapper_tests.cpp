#include <gtest/gtest.h>
#include "container_mapper.h"

// Test runtime detection (mock environment)
TEST(ContainerMapperTest, DefaultConfigUsesDockerSocket) {
    ContainerMapper::Config config;
    EXPECT_EQ(config.dockerSocket, "/var/run/docker.sock");
    EXPECT_EQ(config.containerdSocket, "/run/containerd/containerd.sock");
    EXPECT_EQ(config.crioSocket, "/var/run/crio/crio.sock");
    EXPECT_EQ(config.crictlPath, "/usr/bin/crictl");
    EXPECT_EQ(config.cacheTTLSeconds, 60);
    EXPECT_TRUE(config.enableKubernetesApi);
    EXPECT_TRUE(config.preferPodName);
}

TEST(ContainerMapperTest, ConfigCanBeCustomized) {
    ContainerMapper::Config config;
    config.dockerSocket = "/custom/docker.sock";
    config.containerdSocket = "/custom/containerd.sock";
    config.cacheTTLSeconds = 120;
    config.enableKubernetesApi = false;
    config.preferPodName = false;
    
    EXPECT_EQ(config.dockerSocket, "/custom/docker.sock");
    EXPECT_EQ(config.containerdSocket, "/custom/containerd.sock");
    EXPECT_EQ(config.cacheTTLSeconds, 120);
    EXPECT_FALSE(config.enableKubernetesApi);
    EXPECT_FALSE(config.preferPodName);
}

// Test ContainerInfo structure
TEST(ContainerMapperTest, ContainerInfoDefaultsToEmpty) {
    ContainerMapper::ContainerInfo info;
    EXPECT_TRUE(info.id.empty());
    EXPECT_TRUE(info.fullId.empty());
    EXPECT_TRUE(info.name.empty());
    EXPECT_TRUE(info.podName.empty());
    EXPECT_TRUE(info.podNamespace.empty());
    EXPECT_TRUE(info.image.empty());
}

// Test that mapper can be constructed without crashing
TEST(ContainerMapperTest, CanConstruct) {
    ContainerMapper::Config config;
    // Use non-existent sockets to avoid real lookups
    config.dockerSocket = "/nonexistent/docker.sock";
    config.containerdSocket = "/nonexistent/containerd.sock";
    config.crioSocket = "/nonexistent/crio.sock";
    
    // Should not crash even with invalid sockets
    ContainerMapper mapper(config);
    
    // Detect runtime should return UNKNOWN with no valid sockets
    EXPECT_EQ(mapper.detectRuntime(), ContainerMapper::Runtime::UNKNOWN);
}

// Test cache clearing
TEST(ContainerMapperTest, ClearCacheDoesNotCrash) {
    ContainerMapper::Config config;
    config.dockerSocket = "/nonexistent/docker.sock";
    
    ContainerMapper mapper(config);
    mapper.clearCache();  // Should not crash
}

// Test getContainerName with invalid PID returns empty
TEST(ContainerMapperTest, InvalidPidReturnsEmpty) {
    ContainerMapper::Config config;
    config.dockerSocket = "/nonexistent/docker.sock";
    
    ContainerMapper mapper(config);
    
    // PID 99999999 likely doesn't exist
    std::string name = mapper.getContainerName(12345, 99999999);
    EXPECT_TRUE(name.empty());
}

// Test isContainer with invalid PID
TEST(ContainerMapperTest, IsContainerInvalidPid) {
    ContainerMapper::Config config;
    config.dockerSocket = "/nonexistent/docker.sock";
    
    ContainerMapper mapper(config);
    
    // Non-existent PID should not be detected as container
    EXPECT_FALSE(mapper.isContainer(99999999));
}

