package com.deepkernel.contracts.model;

import com.deepkernel.contracts.model.enums.TriageStatus;

import java.time.Instant;

public record AnomalyWindow(
        String id,
        String containerId,
        Instant windowStart,
        Instant windowEnd,
        double mlScore,
        boolean anomalous,
        TriageStatus triageStatus,
        String triageResultId
) {}

