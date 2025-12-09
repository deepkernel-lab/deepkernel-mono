cmake_minimum_required(VERSION 3.16)
project(deepkernel-agent LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(deepkernel-agent
    src/main.cpp
)

target_include_directories(deepkernel-agent PRIVATE include)

# TODO: integrate libbpf, eBPF object build, and link flags when logic is added.

