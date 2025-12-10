cmake_minimum_required(VERSION 3.16)
project(deepkernel-agent LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBBPF REQUIRED libbpf)
find_package(CURL REQUIRED)

set(BPF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ebpf)
set(BPF_OBJ ${CMAKE_CURRENT_BINARY_DIR}/deepkernel.bpf.o)

# Build eBPF object with clang targeting BPF.
add_custom_command(
    OUTPUT ${BPF_OBJ}
    COMMAND clang -g -O2 -target bpf
            -D__TARGET_ARCH_x86
            -I${BPF_DIR}
            -c ${BPF_DIR}/deepkernel.bpf.c -o ${BPF_OBJ}
    DEPENDS ${BPF_DIR}/deepkernel.bpf.c
    COMMENT "Building eBPF object deepkernel.bpf.o"
)

add_custom_target(bpf_obj ALL DEPENDS ${BPF_OBJ})

add_executable(deepkernel-agent
    src/main.cpp
    src/agent.cpp
    src/config.cpp
    src/http_client.cpp
    src/serialization.cpp
)

add_dependencies(deepkernel-agent bpf_obj)

target_include_directories(deepkernel-agent PRIVATE
    include
    ${LIBBPF_INCLUDE_DIRS}
)

target_link_libraries(deepkernel-agent PRIVATE
    ${LIBBPF_LIBRARIES}
    CURL::libcurl
)

target_compile_definitions(deepkernel-agent PRIVATE _GNU_SOURCE)

add_custom_command(
    TARGET deepkernel-agent POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy ${BPF_OBJ} $<TARGET_FILE_DIR:deepkernel-agent>/deepkernel.bpf.o
    COMMENT "Copying eBPF object next to binary"
)

# ------------------------------
# Tests
# ------------------------------
include(FetchContent)
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.14.0.zip
)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()

add_executable(agent-tests
    src/serialization.cpp
    tests/serialization_tests.cpp
)
target_include_directories(agent-tests PRIVATE include ${LIBBPF_INCLUDE_DIRS})
target_link_libraries(agent-tests PRIVATE GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(agent-tests)

