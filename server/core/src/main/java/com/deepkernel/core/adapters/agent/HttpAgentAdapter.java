package com.deepkernel.core.adapters.agent;

import com.deepkernel.contracts.model.LongDumpComplete;
import com.deepkernel.contracts.model.LongDumpRequest;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.ports.AgentControlPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP adapter to communicate with the eBPF agent's REST API.
 * Sends long dump requests and policy enforcement commands.
 */
public class HttpAgentAdapter implements AgentControlPort {
    private static final Logger log = LoggerFactory.getLogger(HttpAgentAdapter.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    public HttpAgentAdapter(RestTemplate restTemplate,
                            @Value("${deepkernel.agent.base-url:http://localhost:8082}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void requestLongDump(String agentId, String containerId, LongDumpRequest request) {
        String url = baseUrl + "/long-dump-requests";
        
        Map<String, Object> body = new HashMap<>();
        body.put("container_id", containerId);
        body.put("duration_sec", request.durationSec());
        body.put("reason", request.reason());
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            
            restTemplate.postForEntity(url, entity, Void.class);
            log.info("Requested long dump for container {} ({}s)", containerId, request.durationSec());
        } catch (Exception ex) {
            log.warn("Failed to request long dump for {}: {}", containerId, ex.getMessage());
        }
    }

    @Override
    public void notifyLongDumpComplete(LongDumpComplete completion) {
        // Agent calls server with completion; this method handles internal notification.
        log.info("Long dump complete for container {}: {} ({} sec)", 
                completion.containerId(), completion.dumpPath(), completion.durationSec());
    }

    @Override
    public void applyPolicy(String agentId, String containerId, Policy policy) {
        String url = baseUrl + "/api/v1/agent/" + agentId + "/containers/" + containerId + "/policies";
        
        Map<String, Object> body = new HashMap<>();
        body.put("container_id", containerId);
        body.put("policy_id", policy.id());
        body.put("policy_type", policy.type().name());
        
        try {
            body.put("spec_json", objectMapper.writeValueAsString(policy.spec()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize policy spec: {}", e.getMessage());
            return;
        }
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
            log.info("Applied policy {} ({}) to container {}", policy.id(), policy.type(), containerId);
        } catch (Exception ex) {
            log.warn("Failed to apply policy {} for {}: {}", policy.id(), containerId, ex.getMessage());
        }
    }
}

