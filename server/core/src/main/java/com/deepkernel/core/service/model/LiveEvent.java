package com.deepkernel.core.service.model;

import java.time.Instant;
import java.util.Map;

public record LiveEvent(
        String type,
        String containerId,
        Instant timestamp,
        Map<String, Object> payload
) {}

