package com.deepkernel.core.adapters.agent;

import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.contracts.model.enums.PolicyType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpAgentAdapterTest {

    @Test
    void applyPolicyPostsToAgent() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        HttpAgentAdapter adapter = new HttpAgentAdapter(restTemplate, "http://localhost:8080");

        Policy policy = new Policy("p1", "c1", PolicyType.SECCOMP, Map.of(), Instant.now(), "node1", PolicyStatus.PENDING);

        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.accepted().build());

        adapter.applyPolicy("agent-1", "c1", policy);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class));
        assertTrue(urlCaptor.getValue().contains("/api/v1/agent/agent-1/containers/c1/policies"));
    }
}

