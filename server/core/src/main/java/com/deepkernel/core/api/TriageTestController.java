package com.deepkernel.core.api;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.contracts.model.enums.TriageStatus;
import com.deepkernel.contracts.ports.TriagePort;
import com.deepkernel.core.repo.AnomalyWindowRepository;
import com.deepkernel.core.repo.TriageResultRepository;
import com.deepkernel.core.repo.EventRepository;
import com.deepkernel.core.service.model.LiveEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
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
    private final AnomalyWindowRepository windowRepository;
    private final TriageResultRepository triageResultRepository;
    private final EventRepository eventRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public TriageTestController(TriagePort triagePort,
                                AnomalyWindowRepository windowRepository,
                                TriageResultRepository triageResultRepository,
                                EventRepository eventRepository,
                                SimpMessagingTemplate messagingTemplate) {
        this.triagePort = triagePort;
        this.windowRepository = windowRepository;
        this.triageResultRepository = triageResultRepository;
        this.eventRepository = eventRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping
    public ResponseEntity<?> triage(@RequestBody Map<String, Object> body) {
        String containerId = (String) body.getOrDefault("container_id", body.getOrDefault("containerId", "demo"));
        double mlScore = ((Number) body.getOrDefault("ml_score", body.getOrDefault("mlScore", 0.9))).doubleValue();
        boolean anomalous = toBool(body.getOrDefault("is_anomalous", body.getOrDefault("anomalous", true)));
        String syscallSummary = (String) body.getOrDefault("syscall_summary", "(none)");
        String diffSummary = (String) body.getOrDefault("diff_summary", "");
        List<String> changedFiles = toStringList(body.getOrDefault("changed_files", List.of()));

        // 1. Create and save the fake anomaly window
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
        windowRepository.save(window);

        // 2. Build context
        ChangeContext ctx = new ChangeContext(
                containerId,
                (String) body.getOrDefault("commit_id", "demo"),
                (String) body.getOrDefault("repo_url", "demo"),
                changedFiles,
                diffSummary + "\n\nSYSCALL_WINDOW_SUMMARY:\n" + syscallSummary,
                Instant.now()
        );

        // 3. Call LLM
        TriageResult result = triagePort.triage(window, ctx);
        triageResultRepository.save(result);

        // 4. Update window status based on verdict
        AnomalyWindow triagedWindow = new AnomalyWindow(
                window.id(),
                window.containerId(),
                window.windowStart(),
                window.windowEnd(),
                window.mlScore(),
                window.anomalous(),
                "THREAT".equalsIgnoreCase(result.verdict()) ? TriageStatus.THREAT :
                        "SAFE".equalsIgnoreCase(result.verdict()) ? TriageStatus.SAFE : TriageStatus.UNKNOWN,
                result.id()
        );
        windowRepository.save(triagedWindow);

        // 5. Publish event to UI
        LiveEvent triageEvent = new LiveEvent(
                "TRIAGE_RESULT",
                containerId,
                Instant.now(),
                Map.of(
                    "verdict", result.verdict(), 
                    "risk_score", result.riskScore(),
                    "explanation", result.explanation(),
                    "containerId", containerId
                )
        );
        eventRepository.save(triageEvent);
        messagingTemplate.convertAndSend("/topic/events", triageEvent);

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
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return List.of();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }
}
