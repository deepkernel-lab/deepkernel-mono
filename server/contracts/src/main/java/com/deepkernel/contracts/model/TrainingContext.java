package com.deepkernel.contracts.model;

public record TrainingContext(
        String reason,
        int minRecordsPerWindow
) {}

