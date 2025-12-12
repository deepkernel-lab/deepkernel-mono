package com.deepkernel.core.api;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.model.Container;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.LongDumpComplete;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.ShortWindowPayload;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.contracts.model.enums.ModelStatus;
import com.deepkernel.contracts.model.enums.TriageStatus;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.core.api.dto.AgentWindowResponse;
import com.deepkernel.contracts.ports.AgentControlPort;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import com.deepkernel.contracts.ports.ChangeContextPort;
import com.deepkernel.contracts.ports.PolicyGeneratorPort;
import com.deepkernel.contracts.ports.TriagePort;
import com.deepkernel.core.repo.AnomalyWindowRepository;
import com.deepkernel.core.repo.ContainerRepository;
import com.deepkernel.core.repo.EventRepository;
import com.deepkernel.core.repo.PolicyRepository;
import com.deepkernel.core.repo.TriageResultRepository;
import com.deepkernel.core.service.FeatureExtractor;
import com.deepkernel.core.service.SyscallWindowSummarizer;
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
    private final ChangeContextPort changeContextPort;
    private final AnomalyWindowRepository windowRepository;
    private final TriageResultRepository triageResultRepository;
    private final PolicyRepository policyRepository;
    private final EventRepository eventRepository;
    private final ContainerRepository containerRepository;
    private final SyscallWindowSummarizer syscallWindowSummarizer = new SyscallWindowSummarizer();

    public AgentWindowController(AnomalyDetectionPort anomalyDetectionPort,
                                 FeatureExtractor featureExtractor,
                                 SimpMessagingTemplate messagingTemplate,
                                 TriagePort triagePort,
                                 PolicyGeneratorPort policyGeneratorPort,
                                 AgentControlPort agentControlPort,
                                 ChangeContextPort changeContextPort,
                                 AnomalyWindowRepository windowRepository,
                                 TriageResultRepository triageResultRepository,
                                 PolicyRepository policyRepository,
                                 EventRepository eventRepository,
                                 ContainerRepository containerRepository) {
        this.anomalyDetectionPort = anomalyDetectionPort;
        this.featureExtractor = featureExtractor;
        this.messagingTemplate = messagingTemplate;
        this.triagePort = triagePort;
        this.policyGeneratorPort = policyGeneratorPort;
        this.agentControlPort = agentControlPort;
        this.changeContextPort = changeContextPort;
        this.windowRepository = windowRepository;
        this.triageResultRepository = triageResultRepository;
        this.policyRepository = policyRepository;
        this.eventRepository = eventRepository;
        this.containerRepository = containerRepository;
    }

    @PostMapping("/windows")
    public ResponseEntity<?> ingestWindow(@RequestBody ShortWindowPayload payload) {
        int count = payload.records() != null ? payload.records().size() : 0;
        log.info("Received window for container {} from agent {} (records: {})", 
                payload.containerId(), payload.agentId(), count);

        // Auto-register container if not known
        ensureContainerRegistered(payload.containerId(), payload.agentId());

        // Extract features and score
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

        // Publish window scored event
        LiveEvent scoredEvent = new LiveEvent(
                "WINDOW_SCORED",
                payload.containerId(),
                window.windowEnd(),
                Map.of(
                    "ml_score", score.score(), 
                    "is_anomalous", score.anomalous(),
                    "record_count", count
                )
        );
        eventRepository.save(scoredEvent);
        messagingTemplate.convertAndSend("/topic/events", scoredEvent);

        // Get change context for triage
        ChangeContext changeContext = null;
        try {
            changeContext = changeContextPort.getChangeContext(
                payload.containerId(), 
                window.windowStart().minusSeconds(1800) // Look back 30 minutes
            );
        } catch (Exception e) {
            log.debug("Could not retrieve change context: {}", e.getMessage());
        }

        // Attach syscall window summary to change context for LLM triage
        String syscallSummary = syscallWindowSummarizer.summarize(payload.records());
        if (changeContext != null) {
            changeContext = new ChangeContext(
                    changeContext.containerId(),
                    changeContext.commitId(),
                    changeContext.repoUrl(),
                    changeContext.changedFiles(),
                    (changeContext.diffSummary() == null ? "" : changeContext.diffSummary())
                            + "\n\nSYSCALL_WINDOW_SUMMARY:\n" + syscallSummary,
                    changeContext.deployedAt()
            );
        } else {
            // Create a minimal context so LLM always sees syscall window info
            changeContext = new ChangeContext(
                    payload.containerId(),
                    "unknown",
                    "unknown",
                    java.util.List.of(),
                    "SYSCALL_WINDOW_SUMMARY:\n" + syscallSummary,
                    null
            );
        }

        // Perform triage
        TriageResult triage = triagePort.triage(window, changeContext);
        triageResultRepository.save(triage);
        
        // Update window with triage result
        AnomalyWindow triagedWindow = new AnomalyWindow(
                window.id(),
                window.containerId(),
                window.windowStart(),
                window.windowEnd(),
                window.mlScore(),
                window.anomalous(),
                "THREAT".equalsIgnoreCase(triage.verdict()) ? TriageStatus.THREAT : 
                    "SAFE".equalsIgnoreCase(triage.verdict()) ? TriageStatus.SAFE : TriageStatus.UNKNOWN,
                triage.id()
        );
        windowRepository.save(triagedWindow);

        // Publish triage event
        LiveEvent triageEvent = new LiveEvent(
                "TRIAGE_RESULT",
                payload.containerId(),
                Instant.now(),
                Map.of(
                    "verdict", triage.verdict(), 
                    "risk_score", triage.riskScore(),
                    "explanation", triage.explanation()
                )
        );
        eventRepository.save(triageEvent);
        messagingTemplate.convertAndSend("/topic/events", triageEvent);

        // Generate and apply policy if threat detected
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
                    Map.of(
                        "policy_id", applied.id(), 
                        "type", applied.type().name(),
                        "status", applied.status().name()
                    )
            );
            eventRepository.save(policyEvent);
            messagingTemplate.convertAndSend("/topic/events", policyEvent);
            
            // Send policy to agent for enforcement
            agentControlPort.applyPolicy(payload.agentId(), payload.containerId(), applied);
        }

        String status = score.anomalous() ? "anomalous" : "accepted";
        log.info("Window processed for {}: {} (score={:.3f}, verdict={})", 
                payload.containerId(), status, score.score(), triage.verdict());
        
        return ResponseEntity.accepted()
                .body(new AgentWindowResponse(status));
    }

    /**
     * Receives long dump completion notification from the agent.
     * Triggers training pipeline if needed.
     */
    @PostMapping("/dump-complete")
    public ResponseEntity<?> handleDumpComplete(@RequestBody LongDumpComplete completion) {
        log.info("Long dump complete for container {}: path={}, duration={}s", 
                completion.containerId(), completion.dumpPath(), completion.durationSec());
        
        // Notify internal systems
        agentControlPort.notifyLongDumpComplete(completion);
        
        // Publish event for UI
        LiveEvent dumpEvent = new LiveEvent(
                "LONG_DUMP_COMPLETE",
                completion.containerId(),
                Instant.now(),
                Map.of(
                    "dump_path", completion.dumpPath(),
                    "duration_sec", completion.durationSec(),
                    "agent_id", completion.agentId()
                )
        );
        eventRepository.save(dumpEvent);
        messagingTemplate.convertAndSend("/topic/events", dumpEvent);
        
        // TODO: Trigger training pipeline
        // 1. Read binary dump file from completion.dumpPath()
        // 2. Parse trace records
        // 3. Extract feature vectors
        // 4. Train/retrain model for container
        
        return ResponseEntity.ok(Map.of(
            "status", "received",
            "container_id", completion.containerId(),
            "message", "Long dump received; training will be triggered"
        ));
    }

    /**
     * Ensures the container is registered in the repository.
     * Auto-creates if not found.
     */
    private void ensureContainerRegistered(String containerId, String agentId) {
        // Check if container exists
        boolean exists = containerRepository.findAll().stream()
                .anyMatch(c -> c.id().equals(containerId));
        
        if (!exists) {
            // Extract namespace from containerId if format is "namespace/name"
            String namespace = "default";
            String name = containerId;
            if (containerId.contains("/")) {
                String[] parts = containerId.split("/", 2);
                namespace = parts[0];
                name = parts[1];
            }
            
            Container newContainer = new Container(
                containerId,
                namespace,
                agentId != null ? agentId : "unknown",
                "Running",
                true,
                ModelStatus.UNTRAINED
            );
            containerRepository.upsert(newContainer);
            
            log.info("Auto-registered new container: {} (namespace={})", containerId, namespace);
            
            // Publish container discovery event
            LiveEvent discoveryEvent = new LiveEvent(
                    "CONTAINER_DISCOVERED",
                    containerId,
                    Instant.now(),
                    Map.of(
                        "namespace", namespace,
                        "agent_id", agentId != null ? agentId : "unknown"
                    )
            );
            eventRepository.save(discoveryEvent);
            messagingTemplate.convertAndSend("/topic/events", discoveryEvent);
        }
    }
}
