package com.deepkernel.core.adapters.agent;

import com.deepkernel.contracts.model.LongDumpComplete;
import com.deepkernel.contracts.model.LongDumpRequest;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.core.ports.AgentControlPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Basic HTTP adapter to call the agent REST API.
 */
public class HttpAgentAdapter implements AgentControlPort {
    private static final Logger log = LoggerFactory.getLogger(HttpAgentAdapter.class);

    private final RestTemplate restTemplate;

    public HttpAgentAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private String baseUrl(String agentId) {
        // In real deployment this would resolve agent endpoint (service per node).
        return "http://" + agentId + ":8080";
    }

    @Override
    public void requestLongDump(String agentId, String containerId, LongDumpRequest request) {
        String url = baseUrl(agentId) + "/api/v1/agent/" + agentId + "/containers/" + containerId + "/long-dump-requests";
        try {
            restTemplate.postForEntity(url, request, Void.class);
        } catch (Exception ex) {
            log.warn("Failed to request long dump: {}", ex.getMessage());
        }
    }

    @Override
    public void notifyLongDumpComplete(LongDumpComplete completion) {
        // Typically agent calls server, but this method could forward to another system; keep no-op.
        log.info("Long dump complete notification: {}", completion.dumpPath());
    }

    @Override
    public void applyPolicy(String agentId, String containerId, Policy policy) {
        String url = baseUrl(agentId) + "/api/v1/agent/" + agentId + "/containers/" + containerId + "/policies";
        try {
            HttpEntity<Policy> entity = new HttpEntity<>(policy);
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception ex) {
            log.warn("Failed to apply policy: {}", ex.getMessage());
        }
    }
}

