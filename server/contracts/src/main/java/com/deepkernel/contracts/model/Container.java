package com.deepkernel.contracts.model;

import com.deepkernel.contracts.model.enums.ModelStatus;

public record Container(
        String id,
        String namespace,
        String node,
        String status,
        boolean agentConnected,
        ModelStatus modelStatus
) {}

