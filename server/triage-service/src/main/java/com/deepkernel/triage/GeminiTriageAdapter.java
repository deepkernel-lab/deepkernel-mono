package com.deepkernel.triage;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.contracts.ports.TriagePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Triage adapter that uses Google Gemini API for intelligent anomaly triage.
 * Falls back to heuristic-based triage if API is unavailable or not configured.
 */
public class GeminiTriageAdapter implements TriagePort {
    private static final Logger log = LoggerFactory.getLogger(GeminiTriageAdapter.class);
    
    private static final String GEMINI_URL_TEMPLATE = 
        "https://generativelanguage.googleapis.com/v1/models/%s:generateContent?key=%s";
    
    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    public GeminiTriageAdapter() {
        this(System.getenv("GEMINI_API_KEY"), "gemini-pro");
    }
    
    public GeminiTriageAdapter(
            @Value("${deepkernel.gemini.api-key:}") String apiKey,
            @Value("${deepkernel.gemini.model:gemini-pro}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public TriageResult triage(AnomalyWindow window, ChangeContext changeContext) {
        // If API key is configured, try LLM triage
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return triageWithGemini(window, changeContext);
            } catch (Exception e) {
                log.warn("Gemini triage failed, falling back to heuristic: {}", e.getMessage());
            }
        }
        
        // Fallback to heuristic-based triage
        return triageWithHeuristics(window, changeContext);
    }
    
    private TriageResult triageWithGemini(AnomalyWindow window, ChangeContext changeContext) {
        String prompt = buildPrompt(window, changeContext);
        String url = String.format(GEMINI_URL_TEMPLATE, model, apiKey);
        
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(Map.of(
                "parts", List.of(Map.of("text", prompt))
            )),
            "generationConfig", Map.of(
                "temperature", 0.2,
                "maxOutputTokens", 1024
            )
        );
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        String response = restTemplate.postForObject(url, entity, String.class);
        log.debug("Gemini response: {}", response);
        
        return parseGeminiResponse(window, response);
    }
    
    private String buildPrompt(AnomalyWindow window, ChangeContext changeContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a security analyst triaging anomalous syscall behavior in a container.\n\n");
        sb.append("ANOMALY WINDOW:\n");
        sb.append("- Container: ").append(window.containerId()).append("\n");
        sb.append("- Time: ").append(window.windowStart()).append(" to ").append(window.windowEnd()).append("\n");
        sb.append("- ML Anomaly Score: ").append(String.format("%.3f", window.mlScore())).append("\n");
        sb.append("- Anomalous: ").append(window.anomalous()).append("\n\n");
        
        if (changeContext != null) {
            sb.append("RECENT CODE CHANGES:\n");
            sb.append("- Commit: ").append(changeContext.commitId()).append("\n");
            sb.append("- Repository: ").append(changeContext.repoUrl()).append("\n");
            sb.append("- Changed Files: ").append(String.join(", ", changeContext.changedFiles())).append("\n");
            sb.append("- Summary: ").append(changeContext.diffSummary()).append("\n");
            sb.append("- Deployed At: ").append(changeContext.deployedAt()).append("\n\n");
        } else {
            sb.append("RECENT CODE CHANGES: None available\n\n");
        }
        
        sb.append("Based on this information, provide a security triage assessment.\n");
        sb.append("Respond in this exact JSON format:\n");
        sb.append("{\n");
        sb.append("  \"verdict\": \"THREAT\" or \"SAFE\",\n");
        sb.append("  \"risk_score\": 0.0 to 1.0,\n");
        sb.append("  \"explanation\": \"Brief explanation of the assessment\"\n");
        sb.append("}\n");
        
        return sb.toString();
    }
    
    private TriageResult parseGeminiResponse(AnomalyWindow window, String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String textContent = root
                .path("candidates").get(0)
                .path("content")
                .path("parts").get(0)
                .path("text").asText();
            
            // Extract JSON from the response (may be wrapped in markdown)
            String jsonStr = extractJson(textContent);
            JsonNode parsed = objectMapper.readTree(jsonStr);
            
            String verdict = parsed.path("verdict").asText("UNKNOWN");
            double riskScore = parsed.path("risk_score").asDouble(0.5);
            String explanation = parsed.path("explanation").asText("No explanation provided.");
            
            return new TriageResult(
                UUID.randomUUID().toString(),
                window.containerId(),
                window.id(),
                riskScore,
                verdict.toUpperCase(),
                explanation,
                response
            );
        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            // Return a conservative result on parse failure
            return new TriageResult(
                UUID.randomUUID().toString(),
                window.containerId(),
                window.id(),
                0.7,
                "UNKNOWN",
                "Failed to parse LLM response: " + e.getMessage(),
                response
            );
        }
    }
    
    private String extractJson(String text) {
        // Try to extract JSON from markdown code blocks
        Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // If no code block, assume the text is JSON
        return text.trim();
    }
    
    /**
     * Heuristic-based triage for when Gemini API is unavailable.
     * Uses ML score and change context to determine verdict.
     */
    private TriageResult triageWithHeuristics(AnomalyWindow window, ChangeContext changeContext) {
        double mlScore = Math.abs(window.mlScore());
        boolean hasRecentDeploy = changeContext != null && changeContext.deployedAt() != null;
        
        String verdict;
        double riskScore;
        String explanation;
        
        if (!window.anomalous()) {
            // Not flagged as anomalous by ML
            verdict = "SAFE";
            riskScore = Math.max(0.1, mlScore * 0.3);
            explanation = "Syscall patterns within normal behavioral baseline.";
        } else if (hasRecentDeploy) {
            // Anomalous but has recent deployment - likely legitimate change
            verdict = "SAFE";
            riskScore = Math.min(0.5, mlScore * 0.6);
            explanation = String.format(
                "Anomalous behavior detected but correlates with recent deployment (commit %s). " +
                "Changed files: %s. Recommend monitoring but likely legitimate.",
                changeContext.commitId(),
                String.join(", ", changeContext.changedFiles())
            );
        } else if (mlScore > 0.8) {
            // High anomaly score without deployment context
            verdict = "THREAT";
            riskScore = Math.min(0.95, mlScore);
            explanation = String.format(
                "High anomaly score (%.2f) with no recent code changes to explain behavior. " +
                "Syscall patterns deviate significantly from baseline. Recommend immediate investigation.",
                mlScore
            );
        } else if (mlScore > 0.5) {
            // Moderate anomaly score
            verdict = "THREAT";
            riskScore = mlScore * 0.8;
            explanation = String.format(
                "Moderate anomaly score (%.2f) detected. Unusual syscall patterns observed. " +
                "No deployment context available. Recommend policy enforcement.",
                mlScore
            );
        } else {
            // Low anomaly score but still flagged
            verdict = "SAFE";
            riskScore = mlScore * 0.5;
            explanation = "Low anomaly score; flagged but within acceptable variance. Monitoring only.";
        }
        
        log.info("Heuristic triage for {}: {} (risk={:.2f})", window.containerId(), verdict, riskScore);
        
        return new TriageResult(
            UUID.randomUUID().toString(),
            window.containerId(),
            window.id(),
            riskScore,
            verdict,
            explanation,
            null
        );
    }
}
