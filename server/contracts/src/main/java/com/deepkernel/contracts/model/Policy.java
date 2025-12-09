package com.deepkernel.contracts.model;

import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.contracts.model.enums.PolicyType;

import java.time.Instant;
import java.util.Map;

public record Policy(
        String id,
        String containerId,
        PolicyType type,
        Map<String, Object> spec,
        Instant appliedAt,
        String node,
        PolicyStatus status
) {}

