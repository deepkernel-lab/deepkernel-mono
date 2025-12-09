package com.deepkernel.contracts.model;

import java.util.List;

public record ShortWindowPayload(
        int version,
        String agentId,
        String containerId,
        long windowStartTsNs,
        List<TraceRecord> records
) {}

