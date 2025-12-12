package com.deepkernel.core.api.dto;

import com.deepkernel.contracts.model.enums.ModelStatus;

public record UiContainerView(
        String id,
        String namespace,
        String node,
        String status,
        boolean agentConnected,
        ModelStatus modelStatus,
        String lastVerdict,
        Double lastScore,
        String lastExplanation,
        String lastDeploy,
        String policyStatus,
        String policyType
) {}

