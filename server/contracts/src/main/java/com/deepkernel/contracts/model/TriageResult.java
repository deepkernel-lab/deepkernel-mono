package com.deepkernel.contracts.model;

public record TriageResult(
        String id,
        String containerId,
        String windowId,
        double riskScore,
        String verdict,
        String explanation,
        String llmResponseRaw
) {}

