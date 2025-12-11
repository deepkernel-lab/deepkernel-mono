package com.deepkernel.contracts.model;

import java.time.Instant;
import java.util.List;

public record ChangeContext(
        String containerId,
        String commitId,
        String repoUrl,
        List<String> changedFiles,
        String diffSummary,
        Instant deployedAt
) {}

