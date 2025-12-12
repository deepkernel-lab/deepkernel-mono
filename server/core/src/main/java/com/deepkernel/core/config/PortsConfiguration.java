package com.deepkernel.core.config;

import com.deepkernel.anomaly.engine.HybridAnomalyAdapter;
import com.deepkernel.anomaly.engine.InProcessIsolationForestAdapter;
import com.deepkernel.anomaly.engine.RemoteMlAdapter;
import com.deepkernel.cicd.GitHubChangeContextAdapter;
import com.deepkernel.core.adapters.agent.HttpAgentAdapter;
import com.deepkernel.contracts.ports.AgentControlPort;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import com.deepkernel.contracts.ports.ChangeContextPort;
import com.deepkernel.contracts.ports.PolicyGeneratorPort;
import com.deepkernel.contracts.ports.TriagePort;
import com.deepkernel.policy.DefaultPolicyGenerator;
import com.deepkernel.triage.GeminiTriageAdapter;
import com.deepkernel.core.service.TriageToggleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for ports and adapters.
 * 
 * Anomaly Detection Modes:
 * - LOCAL: Uses InProcessIsolationForestAdapter (heuristic-based, always available)
 * - REMOTE: Uses RemoteMlAdapter (calls ml-service, requires running Python service)
 * - HYBRID: Uses HybridAnomalyAdapter (tries remote first, falls back to local)
 * 
 * Set ANOMALY_ENGINE_MODE or deepkernel.anomaly.mode to configure.
 */
@Configuration
public class PortsConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(PortsConfiguration.class);

    @Value("${deepkernel.agent.base-url:http://localhost:8082}")
    private String agentBaseUrl;
    
    @Value("${deepkernel.gemini.api-key:}")
    private String geminiApiKey;
    
    @Value("${deepkernel.gemini.model:gemini-1.5-flash}")
    private String geminiModel;

    @Value("${deepkernel.triage.enable-llm:false}")
    private boolean enableLlm;
    
    @Value("${deepkernel.anomaly.mode:HYBRID}")
    private String anomalyMode;
    
    @Value("${deepkernel.ml-service.url:http://localhost:8081}")
    private String mlServiceUrl;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public AnomalyDetectionPort anomalyDetectionPort(RestTemplate restTemplate) {
        // Get mode from environment variable or config
        String mode = System.getenv("ANOMALY_ENGINE_MODE");
        if (mode == null || mode.isBlank()) {
            mode = anomalyMode;
        }
        mode = mode.toUpperCase();
        
        log.info("Configuring anomaly detection with mode: {}", mode);
        
        // Create local adapter (always needed as fallback)
        InProcessIsolationForestAdapter localAdapter = new InProcessIsolationForestAdapter();
        
        switch (mode) {
            case "LOCAL":
                log.info("Using LOCAL anomaly detection (InProcessIsolationForestAdapter)");
                return localAdapter;
                
            case "REMOTE":
                log.info("Using REMOTE anomaly detection (RemoteMlAdapter at {})", mlServiceUrl);
                RemoteMlAdapter remoteAdapter = new RemoteMlAdapter(restTemplate, mlServiceUrl);
                // Wrap in hybrid with local fallback for safety
                return new HybridAnomalyAdapter(remoteAdapter, localAdapter, true);
                
            case "HYBRID":
            default:
                log.info("Using HYBRID anomaly detection (remote={}, local fallback)", mlServiceUrl);
                RemoteMlAdapter remote = new RemoteMlAdapter(restTemplate, mlServiceUrl);
                return new HybridAnomalyAdapter(remote, localAdapter, true);
        }
    }

    @Bean
    public TriageToggleService triageToggleService() {
        return new TriageToggleService(enableLlm);
    }

    @Bean
    public TriagePort triagePort(TriageToggleService triageToggleService) {
        return new GeminiTriageAdapter(geminiApiKey, geminiModel, enableLlm, triageToggleService::isEnabled);
    }

    @Bean
    public ChangeContextPort changeContextPort() {
        return new GitHubChangeContextAdapter();
    }

    @Bean
    public PolicyGeneratorPort policyGeneratorPort() {
        return new DefaultPolicyGenerator();
    }

    @Bean
    public AgentControlPort agentControlPort(RestTemplate restTemplate) {
        return new HttpAgentAdapter(restTemplate, agentBaseUrl);
    }
}
