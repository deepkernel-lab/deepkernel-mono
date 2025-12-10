package com.deepkernel.core.api;

import com.deepkernel.contracts.model.*;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.contracts.model.enums.PolicyType;
import com.deepkernel.contracts.model.enums.TriageStatus;
import com.deepkernel.core.api.dto.AgentWindowResponse;
import com.deepkernel.core.ports.AgentControlPort;
import com.deepkernel.core.ports.AnomalyDetectionPort;
import com.deepkernel.core.ports.ChangeContextPort;
import com.deepkernel.core.ports.PolicyGeneratorPort;
import com.deepkernel.core.ports.TriagePort;
import com.deepkernel.core.repo.AnomalyWindowRepository;
import com.deepkernel.core.repo.ContainerRepository;
import com.deepkernel.core.repo.EventRepository;
import com.deepkernel.core.repo.PolicyRepository;
import com.deepkernel.core.repo.TriageResultRepository;
import com.deepkernel.core.service.FeatureExtractor;
import com.deepkernel.core.service.model.LiveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWindowControllerTest {

    private AnomalyDetectionPort anomalyDetectionPort;
    private FeatureExtractor featureExtractor;
    private SimpMessagingTemplate messagingTemplate;
    private TriagePort triagePort;
    private PolicyGeneratorPort policyGeneratorPort;
    private AgentControlPort agentControlPort;
    private ChangeContextPort changeContextPort;
    private AnomalyWindowRepository windowRepository;
    private TriageResultRepository triageResultRepository;
    private PolicyRepository policyRepository;
    private EventRepository eventRepository;
    private ContainerRepository containerRepository;
    private AgentWindowController controller;

    @BeforeEach
    void setup() {
        anomalyDetectionPort = mock(AnomalyDetectionPort.class);
        featureExtractor = new FeatureExtractor();
        messagingTemplate = mock(SimpMessagingTemplate.class);
        triagePort = mock(TriagePort.class);
        policyGeneratorPort = mock(PolicyGeneratorPort.class);
        agentControlPort = mock(AgentControlPort.class);
        changeContextPort = mock(ChangeContextPort.class);
        windowRepository = new AnomalyWindowRepository();
        triageResultRepository = new TriageResultRepository();
        policyRepository = new PolicyRepository();
        eventRepository = new EventRepository();
        containerRepository = new ContainerRepository();
        controller = new AgentWindowController(
                anomalyDetectionPort,
                featureExtractor,
                messagingTemplate,
                triagePort,
                policyGeneratorPort,
                agentControlPort,
                changeContextPort,
                windowRepository,
                triageResultRepository,
                policyRepository,
                eventRepository,
                containerRepository
        );
    }

    @Test
    void pipelineStoresAndAppliesPolicyOnThreat() {
        ShortWindowPayload payload = new ShortWindowPayload(
                1,
                "agent-1",
                "container-1",
                1_000_000L,
                List.of(new TraceRecord(0, 59, 2, 1))
        );

        when(anomalyDetectionPort.scoreWindow(eq("container-1"), any()))
                .thenReturn(new AnomalyScore(-0.9, true));
        TriageResult triage = new TriageResult(
                UUID.randomUUID().toString(),
                "container-1",
                "win-1",
                0.8,
                "THREAT",
                "stub threat",
                null
        );
        when(triagePort.triage(any(), any())).thenReturn(triage);
        Policy policy = new Policy(
                UUID.randomUUID().toString(),
                "container-1",
                PolicyType.SECCOMP,
                Map.of("key", "val"),
                Instant.now(),
                null,
                PolicyStatus.PENDING
        );
        when(policyGeneratorPort.generatePolicy(any(), eq(triage))).thenReturn(policy);

        var response = controller.ingestWindow(payload);
        assertEquals(202, response.getStatusCode().value());
        assertTrue(((AgentWindowResponse) response.getBody()).status().contains("anomalous"));

        // Repositories updated
        assertNotNull(windowRepository.latest("container-1"));
        assertNotNull(triageResultRepository.latestForWindow(triage.windowId()));
        assertNotNull(policyRepository.latest("container-1"));

        // Agent apply policy called
        verify(agentControlPort).applyPolicy(anyString(), eq("container-1"), any());

        // Events published
        verify(messagingTemplate, atLeast(2)).convertAndSend(eq("/topic/events"), any(LiveEvent.class));
    }
    
    @Test
    void autoRegistersUnknownContainer() {
        ShortWindowPayload payload = new ShortWindowPayload(
                1,
                "node-1",
                "prod/new-service",
                1_000_000L,
                List.of(new TraceRecord(0, 2, 0, 0))
        );

        when(anomalyDetectionPort.scoreWindow(anyString(), any()))
                .thenReturn(new AnomalyScore(-0.3, false));
        TriageResult triage = new TriageResult(
                UUID.randomUUID().toString(),
                "prod/new-service",
                "win-1",
                0.2,
                "SAFE",
                "Normal behavior",
                null
        );
        when(triagePort.triage(any(), any())).thenReturn(triage);
        when(policyGeneratorPort.generatePolicy(any(), any())).thenReturn(null);

        controller.ingestWindow(payload);

        // Container should be auto-registered
        boolean found = containerRepository.findAll().stream()
                .anyMatch(c -> c.id().equals("prod/new-service"));
        assertTrue(found, "Container should be auto-registered");
        
        // Verify namespace extraction
        Container container = containerRepository.findAll().stream()
                .filter(c -> c.id().equals("prod/new-service"))
                .findFirst()
                .orElse(null);
        assertNotNull(container);
        assertEquals("prod", container.namespace());
    }
    
    @Test
    void usesChangeContextForTriage() {
        ShortWindowPayload payload = new ShortWindowPayload(
                1,
                "agent-1",
                "container-1",
                1_000_000L,
                List.of(new TraceRecord(0, 59, 2, 1))
        );

        ChangeContext mockContext = new ChangeContext(
                "container-1",
                "abc123",
                "https://github.com/test/repo",
                List.of("src/main.py"),
                "Added new feature",
                Instant.now()
        );
        when(changeContextPort.getChangeContext(anyString(), any())).thenReturn(mockContext);
        when(anomalyDetectionPort.scoreWindow(anyString(), any()))
                .thenReturn(new AnomalyScore(-0.5, true));
        
        ArgumentCaptor<ChangeContext> contextCaptor = ArgumentCaptor.forClass(ChangeContext.class);
        TriageResult triage = new TriageResult(
                UUID.randomUUID().toString(),
                "container-1",
                "win-1",
                0.3,
                "SAFE",
                "Explained by recent deployment",
                null
        );
        when(triagePort.triage(any(), contextCaptor.capture())).thenReturn(triage);
        when(policyGeneratorPort.generatePolicy(any(), any())).thenReturn(null);

        controller.ingestWindow(payload);

        // Verify change context was passed to triage
        ChangeContext capturedContext = contextCaptor.getValue();
        assertNotNull(capturedContext);
        assertEquals("abc123", capturedContext.commitId());
    }
}
