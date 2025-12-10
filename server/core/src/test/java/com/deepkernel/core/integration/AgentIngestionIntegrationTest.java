package com.deepkernel.core.integration;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.ShortWindowPayload;
import com.deepkernel.contracts.model.TraceRecord;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.contracts.model.enums.PolicyType;
import com.deepkernel.core.ports.AgentControlPort;
import com.deepkernel.core.ports.AnomalyDetectionPort;
import com.deepkernel.core.ports.PolicyGeneratorPort;
import com.deepkernel.core.ports.TriagePort;
import com.deepkernel.core.repo.AnomalyWindowRepository;
import com.deepkernel.core.repo.EventRepository;
import com.deepkernel.core.repo.PolicyRepository;
import com.deepkernel.core.repo.TriageResultRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class AgentIngestionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AnomalyWindowRepository windowRepository;
    @Autowired
    private TriageResultRepository triageResultRepository;
    @Autowired
    private PolicyRepository policyRepository;
    @Autowired
    private EventRepository eventRepository;

    @MockBean
    private AnomalyDetectionPort anomalyDetectionPort;
    @MockBean
    private TriagePort triagePort;
    @MockBean
    private PolicyGeneratorPort policyGeneratorPort;
    @MockBean
    private AgentControlPort agentControlPort;

    @Test
    void postWindowStoresPipelineAndEmitsPolicy() {
        ShortWindowPayload payload = new ShortWindowPayload(
                1,
                "agent-1",
                "container-1",
                1_000_000L,
                List.of(new TraceRecord(0, 59, 2, 1), new TraceRecord(100, 2, 0, 0))
        );

        when(anomalyDetectionPort.scoreWindow(eq("container-1"), any()))
                .thenReturn(new AnomalyScore(-0.8, true));

        TriageResult triage = new TriageResult(
                UUID.randomUUID().toString(),
                "container-1",
                "win-1",
                0.9,
                "THREAT",
                "stub threat",
                null
        );
        when(triagePort.triage(any(), any())).thenReturn(triage);

        Policy policy = new Policy(
                UUID.randomUUID().toString(),
                "container-1",
                PolicyType.SECCOMP,
                Map.of(),
                Instant.now(),
                "node-1",
                PolicyStatus.PENDING
        );
        when(policyGeneratorPort.generatePolicy(any(), eq(triage))).thenReturn(policy);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ShortWindowPayload> request = new HttpEntity<>(payload, headers);
        var response = restTemplate.postForEntity("http://localhost:" + port + "/api/v1/agent/windows", request, Object.class);
        assertEquals(202, response.getStatusCodeValue());

        assertNotNull(windowRepository.latest("container-1"));
        assertNotNull(triageResultRepository.latestForWindow(triage.windowId()));
        assertNotNull(policyRepository.latest("container-1"));
        assertFalse(eventRepository.latest(10).isEmpty());

        verify(agentControlPort).applyPolicy(anyString(), eq("container-1"), any());
    }
}
