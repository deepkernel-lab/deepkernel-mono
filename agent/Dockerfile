FROM ubuntu:22.04 AS builder

RUN apt-get update && apt-get install -y \
    clang llvm libbpf-dev libelf-dev zlib1g-dev libcurl4-openssl-dev \
    cmake build-essential pkg-config git linux-headers-generic

WORKDIR /app
# Copy the whole mono-repo context so paths align if needed, or just agent
COPY agent ./agent

# Build Agent
WORKDIR /app/agent
# Fix for vmlinux.h generation if needed, or assume code handles it
# Note: In docker build, we might not have the right kernel headers for the HOST kernel.
# But for CO-RE, we just need a generic vmlinux.h or the one we generated.
# For simplicity, we assume the code is already patched to include "vmlinux.h"
# and we might need to generate it or copy it.
# Let's try to generate a generic one or rely on the one in source if checked in.
RUN mkdir -p ebpf && touch ebpf/vmlinux.h 

RUN cmake -S . -B build && cmake --build build

# Final Stage
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y libelf1 libcurl4 libz1 libbpf0 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/deepkernel
COPY --from=builder /app/agent/build/deepkernel-agent .
COPY --from=builder /app/agent/build/deepkernel.bpf.o .
COPY --from=builder /app/agent/config ./config

RUN mkdir -p /var/lib/deepkernel/dumps /var/lib/deepkernel/policies

ENV DK_AGENT_ID="docker-agent"
ENV DK_SERVER_URL="http://localhost:9090"

CMD ["./deepkernel-agent"]

