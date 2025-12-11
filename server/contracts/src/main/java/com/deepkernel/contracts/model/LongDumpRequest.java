package com.deepkernel.contracts.model;

public record LongDumpRequest(
        int durationSec,
        String reason
) {}

