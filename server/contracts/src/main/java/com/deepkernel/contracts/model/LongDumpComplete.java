package com.deepkernel.contracts.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record LongDumpComplete(
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("container_id") String containerId,
        @JsonProperty("dump_path") String dumpPath,
        @JsonProperty("start_ts") Instant startTs,
        @JsonProperty("duration_sec") int durationSec
) {}

