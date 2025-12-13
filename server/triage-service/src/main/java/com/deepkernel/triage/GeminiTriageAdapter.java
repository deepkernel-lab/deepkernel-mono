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
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Triage adapter that uses Google Gemini API for intelligent anomaly triage.
 * Falls back to heuristic-based triage if API is unavailable or not configured.
 */
public class GeminiTriageAdapter implements TriagePort {
    private static final Logger log = LoggerFactory.getLogger(GeminiTriageAdapter.class);
    
    private static final String GEMINI_URL_TEMPLATE_V1BETA =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final String GEMINI_URL_TEMPLATE_V1 =
        "https://generativelanguage.googleapis.com/v1/models/%s:generateContent?key=%s";
    
    private final String apiKey;
    private final String model;
    private final boolean defaultEnableLlm;
    private final BooleanSupplier enableSupplier;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    public GeminiTriageAdapter() {
        this(System.getenv("GEMINI_API_KEY"), "gemini-2.5-flash", false, () -> false);
    }
    
    public GeminiTriageAdapter(
            @Value("${deepkernel.gemini.api-key:}") String apiKey,
            @Value("${deepkernel.gemini.model:gemini-2.5-flash}") String model,
            @Value("${deepkernel.triage.enable-llm:false}") boolean enableLlm,
            BooleanSupplier enableSupplier) {
        this.apiKey = apiKey;
        this.model = model;
        this.defaultEnableLlm = enableLlm;
        this.enableSupplier = enableSupplier;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public TriageResult triage(AnomalyWindow window, ChangeContext changeContext) {
        boolean llmEnabled = enableSupplier != null ? enableSupplier.getAsBoolean() : defaultEnableLlm;
        // If disabled or missing API key, skip LLM
        if (llmEnabled && apiKey != null && !apiKey.isBlank()) {
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
        String urlV1beta = String.format(GEMINI_URL_TEMPLATE_V1BETA, model, apiKey);
        String urlV1 = String.format(GEMINI_URL_TEMPLATE_V1, model, apiKey);
        
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(Map.of(
                "parts", List.of(Map.of("text", prompt))
            )),
            "generationConfig", Map.of(
                "temperature", 0.2,
                "maxOutputTokens", 256  // keep short to avoid truncation
            )
        );
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        String response;
        try {
            response = restTemplate.postForObject(urlV1beta, entity, String.class);
        } catch (Exception v1betaErr) {
            log.debug("Gemini v1beta call failed (will retry v1): {}", v1betaErr.getMessage());
            response = restTemplate.postForObject(urlV1, entity, String.class);
        }
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
        
        sb.append("TASK:\n");
        sb.append("- Decide if this is a THREAT or SAFE change.\n");
        sb.append("- If THREAT, propose a Seccomp policy that blocks the suspicious behavior.\n");
        sb.append("\n");
        sb.append("Respond ONLY with a single-line JSON (no markdown, no code fences): ");
        sb.append("{\"verdict\":\"THREAT|SAFE\",\"risk_score\":0.0-1.0,\"explanation\":\"...\"}\n");
        sb.append("Keep the response under 200 tokens.\n");
        
        return sb.toString();
    }
    
    private TriageResult parseGeminiResponse(AnomalyWindow window, String response) {
        String rawText = null;
        try {
            JsonNode root = objectMapper.readTree(response);
            rawText = root
                .path("candidates").get(0)
                .path("content")
                .path("parts").get(0)
                .path("text").asText();

            String jsonStr = extractJson(rawText);
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
                parsed.toString()
            );
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response; falling back to heuristic with LLM text. err={}", e.getMessage());
            // Try a regex-based salvage from rawText
            String verdict = "UNKNOWN";
            double risk = 0.7;
            String explanation = rawText != null ? rawText : ("Failed to parse LLM response: " + e.getMessage());

            if (rawText != null) {
                Matcher mv = Pattern.compile("\"verdict\"\\s*:\\s*\"(\\w+)\"", Pattern.CASE_INSENSITIVE).matcher(rawText);
                if (mv.find()) verdict = mv.group(1).toUpperCase();
                Matcher mr = Pattern.compile("\"risk_score\"\\s*:\\s*([0-9.]+)").matcher(rawText);
                if (mr.find()) {
                    try { risk = Double.parseDouble(mr.group(1)); } catch (NumberFormatException ignored) {}
                }
                Matcher me = Pattern.compile("\"explanation\"\\s*:\\s*\"([^\"]*)\"").matcher(rawText);
                if (me.find()) explanation = me.group(1);
            }

            return new TriageResult(
                UUID.randomUUID().toString(),
                window.containerId(),
                window.id(),
                risk,
                verdict,
                explanation,
                rawText != null ? rawText : response
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
