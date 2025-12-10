package com.deepkernel.core.config;

import com.deepkernel.anomaly.engine.InProcessIsolationForestAdapter;
import com.deepkernel.cicd.GitHubChangeContextAdapter;
import com.deepkernel.core.adapters.agent.HttpAgentAdapter;
import com.deepkernel.core.ports.AnomalyDetectionPort;
import com.deepkernel.core.ports.ChangeContextPort;
import com.deepkernel.core.ports.PolicyGeneratorPort;
import com.deepkernel.core.ports.TriagePort;
import com.deepkernel.policy.DefaultPolicyGenerator;
import com.deepkernel.triage.GeminiTriageAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class PortsConfiguration {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public AnomalyDetectionPort anomalyDetectionPort() {
        return new InProcessIsolationForestAdapter();
    }

    @Bean
    public TriagePort triagePort() {
        return new GeminiTriageAdapter();
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
    public HttpAgentAdapter agentControlPort(RestTemplate restTemplate) {
        return new HttpAgentAdapter(restTemplate);
    }
}

