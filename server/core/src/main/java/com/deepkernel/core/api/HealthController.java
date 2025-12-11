package com.deepkernel.core.api;

import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health check endpoint for the DeepKernel server.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final AnomalyDetectionPort anomalyDetectionPort;
    private final Instant startTime = Instant.now();

    public HealthController(AnomalyDetectionPort anomalyDetectionPort) {
        this.anomalyDetectionPort = anomalyDetectionPort;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "timestamp", Instant.now().toString(),
            "uptime_seconds", java.time.Duration.between(startTime, Instant.now()).getSeconds(),
            "components", Map.of(
                "anomaly_engine", "UP",
                "triage_service", "UP",
                "policy_engine", "UP"
            )
        ));
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
            "application", "DeepKernel Server",
            "version", "0.1.0-SNAPSHOT",
            "description", "eBPF-based container runtime security platform",
            "features", Map.of(
                "anomaly_detection", true,
                "llm_triage", true,
                "policy_enforcement", true,
                "websocket_events", true
            )
        ));
    }
}

