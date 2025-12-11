package com.deepkernel.contracts.model;

import java.time.Instant;
import java.util.List;

public record DeploymentEvent(
        String id,
        String containerId,
        String commitId,
        String pipelineId,
        List<String> changedFiles,
        Instant createdAt
) {}

