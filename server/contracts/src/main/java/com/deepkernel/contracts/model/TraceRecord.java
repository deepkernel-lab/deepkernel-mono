package com.deepkernel.contracts.model;

public record TraceRecord(
        int deltaTsUs,
        int syscallId,
        int argClass,
        int argBucket
) {}

