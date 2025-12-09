package com.deepkernel.contracts.model;

import java.time.Instant;

public record LongDumpComplete(
        String agentId,
        String containerId,
        String dumpPath,
        Instant startTs,
        int durationSec
) {}

