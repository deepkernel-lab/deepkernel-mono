package com.deepkernel.contracts.model;

import com.deepkernel.contracts.model.enums.ModelStatus;

import java.time.Instant;
import java.util.Map;

public record ModelVersion(
        String modelId,
        String containerId,
        int version,
        String featureVersion,
        Instant trainedAt,
        ModelStatus status,
        Map<String, Object> metrics
) {}

