package com.deepkernel.core.api;

import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.core.service.TrainingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/ui/train")
public class TrainingController {
    private static final Logger log = LoggerFactory.getLogger(TrainingController.class);

    private final TrainingService trainingService;

    public TrainingController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @PostMapping("/{containerId}")
    public ResponseEntity<?> triggerTraining(@PathVariable("containerId") String containerId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        // Optional agentId + duration override; default to 60s dump
        String agentId = body != null ? (String) body.getOrDefault("agent_id", "agent-1") : "agent-1";
        int duration = 60;
        if (body != null && body.get("duration_sec") instanceof Number n) {
            duration = n.intValue();
        }

        TrainingContext ctx = body != null && body.get("context") instanceof Map ? null : null; // unused now
        try {
            trainingService.startOrchestratedTraining(containerId, agentId, duration, ctx);
            return ResponseEntity.accepted().body(Map.of(
                    "status", "TRAINING",
                    "container_id", containerId,
                    "agent_id", agentId,
                    "duration_sec", duration
            ));
        } catch (Exception e) {
            log.warn("Failed to start training for {}: {}", containerId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "FAILED",
                    "error", e.getMessage()
            ));
        }
    }
}

