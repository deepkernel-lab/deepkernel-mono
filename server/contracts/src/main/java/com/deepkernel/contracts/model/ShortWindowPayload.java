package com.deepkernel.contracts.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ShortWindowPayload(
        int version,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("container_id") String containerId,
        @JsonProperty("window_start_ts_ns") long windowStartTsNs,
        List<TraceRecord> records
) {}

