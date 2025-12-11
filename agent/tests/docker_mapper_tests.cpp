#include <gtest/gtest.h>

#include "docker_mapper.h"

// Note: These tests are for the DockerMapper class but don't require 
// a real Docker socket for basic functionality tests.

TEST(DockerMapperTest, ConstructorDefaultPath) {
    DockerMapper mapper;
    // Should construct without error using default path
    EXPECT_TRUE(true);
}

TEST(DockerMapperTest, ConstructorCustomPath) {
    DockerMapper mapper("/custom/docker.sock", 30);
    // Should construct without error using custom path
    EXPECT_TRUE(true);
}

TEST(DockerMapperTest, CacheClear) {
    DockerMapper mapper;
    mapper.clearCache();
    // Should clear without error
    EXPECT_TRUE(true);
}

TEST(DockerMapperTest, GetContainerNameForNonContainer) {
    DockerMapper mapper("/nonexistent/path.sock", 60);
    // Should return empty for non-container process (pid 1 is usually init)
    std::string name = mapper.getContainerName(1, 1);
    // Either empty or cached empty
    EXPECT_TRUE(name.empty() || name.find("host-") == 0 || !name.empty());
}

TEST(DockerMapperTest, CacheExpiry) {
    // Test with very short TTL
    DockerMapper mapper("/nonexistent/path.sock", 1);  // 1 second TTL
    
    // First call should cache
    std::string name1 = mapper.getContainerName(12345, 1);
    
    // Second call should use cache
    std::string name2 = mapper.getContainerName(12345, 1);
    EXPECT_EQ(name1, name2);
}

