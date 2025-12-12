package com.deepkernel.core.api;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.contracts.model.enums.TriageStatus;
import com.deepkernel.contracts.ports.TriagePort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Demo helper endpoint to trigger LLM triage without waiting for the full agent pipeline.
 * You can paste a syscall summary and change context to show Gemini output live.
 */
@RestController
@RequestMapping("/api/ui/demo/triage")
public class TriageTestController {
    private final TriagePort triagePort;

    public TriageTestController(TriagePort triagePort) {
        this.triagePort = triagePort;
    }

    @PostMapping
    public ResponseEntity<?> triage(@RequestBody Map<String, Object> body) {
        String containerId = (String) body.getOrDefault("container_id", body.getOrDefault("containerId", "demo"));
        double mlScore = ((Number) body.getOrDefault("ml_score", body.getOrDefault("mlScore", 0.9))).doubleValue();
        boolean anomalous = toBool(body.getOrDefault("is_anomalous", body.getOrDefault("anomalous", true)));
        String syscallSummary = (String) body.getOrDefault("syscall_summary", "(none)");
        String diffSummary = (String) body.getOrDefault("diff_summary", "");

        List<String> changedFiles = toStringList(body.getOrDefault("changed_files", List.of()));

        AnomalyWindow window = new AnomalyWindow(
                UUID.randomUUID().toString(),
                containerId,
                Instant.now().minusSeconds(5),
                Instant.now(),
                mlScore,
                anomalous,
                TriageStatus.PENDING,
                null
        );

        ChangeContext ctx = new ChangeContext(
                containerId,
                (String) body.getOrDefault("commit_id", "demo"),
                (String) body.getOrDefault("repo_url", "demo"),
                changedFiles,
                diffSummary + "\n\nSYSCALL_WINDOW_SUMMARY:\n" + syscallSummary,
                Instant.now()
        );

        TriageResult result = triagePort.triage(window, ctx);
        return ResponseEntity.ok(result);
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return String.valueOf(v).trim().equalsIgnoreCase("true");
    }

    private static List<String> toStringList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        // Support comma-separated string input
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return List.of();
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }
}

