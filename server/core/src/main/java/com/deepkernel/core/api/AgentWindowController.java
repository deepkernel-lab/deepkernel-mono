package com.deepkernel.core.api;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.ShortWindowPayload;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.contracts.model.enums.TriageStatus;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.core.api.dto.AgentWindowResponse;
import com.deepkernel.core.ports.AgentControlPort;
import com.deepkernel.core.ports.AnomalyDetectionPort;
import com.deepkernel.core.ports.PolicyGeneratorPort;
import com.deepkernel.core.ports.TriagePort;
import com.deepkernel.core.repo.AnomalyWindowRepository;
import com.deepkernel.core.repo.EventRepository;
import com.deepkernel.core.repo.PolicyRepository;
import com.deepkernel.core.repo.TriageResultRepository;
import com.deepkernel.core.service.FeatureExtractor;
import com.deepkernel.core.service.model.LiveEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentWindowController {

    private static final Logger log = LoggerFactory.getLogger(AgentWindowController.class);
    private final AnomalyDetectionPort anomalyDetectionPort;
    private final FeatureExtractor featureExtractor;
    private final SimpMessagingTemplate messagingTemplate;
    private final TriagePort triagePort;
    private final PolicyGeneratorPort policyGeneratorPort;
    private final AgentControlPort agentControlPort;
    private final AnomalyWindowRepository windowRepository;
    private final TriageResultRepository triageResultRepository;
    private final PolicyRepository policyRepository;
    private final EventRepository eventRepository;

    public AgentWindowController(AnomalyDetectionPort anomalyDetectionPort,
                                 FeatureExtractor featureExtractor,
                                 SimpMessagingTemplate messagingTemplate,
                                 TriagePort triagePort,
                                 PolicyGeneratorPort policyGeneratorPort,
                                 AgentControlPort agentControlPort,
                                 AnomalyWindowRepository windowRepository,
                                 TriageResultRepository triageResultRepository,
                                 PolicyRepository policyRepository,
                                 EventRepository eventRepository) {
        this.anomalyDetectionPort = anomalyDetectionPort;
        this.featureExtractor = featureExtractor;
        this.messagingTemplate = messagingTemplate;
        this.triagePort = triagePort;
        this.policyGeneratorPort = policyGeneratorPort;
        this.agentControlPort = agentControlPort;
        this.windowRepository = windowRepository;
        this.triageResultRepository = triageResultRepository;
        this.policyRepository = policyRepository;
        this.eventRepository = eventRepository;
    }

    @PostMapping("/windows")
    public ResponseEntity<?> ingestWindow(@RequestBody ShortWindowPayload payload) {
        int count = payload.records() != null ? payload.records().size() : 0;
        log.info("Received window for container {} (records: {})", payload.containerId(), count);

        FeatureVector fv = featureExtractor.extract(payload);
        AnomalyScore score = anomalyDetectionPort.scoreWindow(payload.containerId(), fv);

        AnomalyWindow window = new AnomalyWindow(
                UUID.randomUUID().toString(),
                payload.containerId(),
                Instant.ofEpochMilli(payload.windowStartTsNs() / 1_000_000),
                Instant.ofEpochMilli((payload.windowStartTsNs() / 1_000_000) + 5000),
                score.score(),
                score.anomalous(),
                TriageStatus.PENDING,
                null
        );
        windowRepository.save(window);

        LiveEvent scoredEvent = new LiveEvent(
                "WINDOW_SCORED",
                payload.containerId(),
                window.windowEnd(),
                Map.of("ml_score", score.score(), "is_anomalous", score.anomalous())
        );
        eventRepository.save(scoredEvent);
        messagingTemplate.convertAndSend("/topic/events", scoredEvent);

        TriageResult triage = triagePort.triage(window, null);
        triageResultRepository.save(triage);
        // Update window with triage result id/status
        AnomalyWindow triagedWindow = new AnomalyWindow(
                window.id(),
                window.containerId(),
                window.windowStart(),
                window.windowEnd(),
                window.mlScore(),
                window.anomalous(),
                "THREAT".equalsIgnoreCase(triage.verdict()) ? TriageStatus.THREAT : TriageStatus.SAFE,
                triage.id()
        );
        windowRepository.save(triagedWindow);

        LiveEvent triageEvent = new LiveEvent(
                "TRIAGE_RESULT",
                payload.containerId(),
                Instant.now(),
                Map.of("verdict", triage.verdict(), "risk_score", triage.riskScore())
        );
        eventRepository.save(triageEvent);
        messagingTemplate.convertAndSend("/topic/events", triageEvent);

        Policy policy = policyGeneratorPort.generatePolicy(window, triage);
        if (policy != null) {
            Policy applied = new Policy(
                    policy.id(),
                    policy.containerId(),
                    policy.type(),
                    policy.spec(),
                    Instant.now(),
                    policy.node(),
                    PolicyStatus.APPLIED
            );
            policyRepository.save(applied);
            LiveEvent policyEvent = new LiveEvent(
                    "POLICY_APPLIED",
                    payload.containerId(),
                    Instant.now(),
                    Map.of("policy_id", applied.id(), "type", applied.type().name())
            );
            eventRepository.save(policyEvent);
            messagingTemplate.convertAndSend("/topic/events", policyEvent);
            agentControlPort.applyPolicy("localhost-agent", payload.containerId(), applied);
        }

        return ResponseEntity.accepted()
                .body(new AgentWindowResponse(score.anomalous() ? "anomalous" : "accepted"));
    }
}

