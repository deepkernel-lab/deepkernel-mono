package com.deepkernel.contracts.model;

import com.deepkernel.contracts.model.enums.ModelStatus;

public record ModelMeta(
        String modelId,
        String containerId,
        int version,
        String featureVersion,
        ModelStatus status
) {}

