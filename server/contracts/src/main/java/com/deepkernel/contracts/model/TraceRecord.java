package com.deepkernel.contracts.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TraceRecord(
        @JsonProperty("delta_ts_us") int deltaTsUs,
        @JsonProperty("syscall_id") int syscallId,
        @JsonProperty("arg_class") int argClass,
        @JsonProperty("arg_bucket") int argBucket
) {}

