package com.deepkernel.anomaly.engine;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.contracts.model.enums.ModelStatus;
import com.deepkernel.core.ports.AnomalyDetectionPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Remote ML adapter that calls the Python ml-service for anomaly detection.
 * 
 * Implements the AnomalyDetectionPort interface by delegating to the
 * ml-service REST API running on a configurable URL.
 */
public class RemoteMlAdapter implements AnomalyDetectionPort {
    private static final Logger log = LoggerFactory.getLogger(RemoteMlAdapter.class);
    
    private final RestTemplate restTemplate;
    private final String mlServiceUrl;
    private final ObjectMapper objectMapper;
    
    public RemoteMlAdapter(RestTemplate restTemplate, String mlServiceUrl) {
        this.restTemplate = restTemplate;
        this.mlServiceUrl = mlServiceUrl.endsWith("/") 
            ? mlServiceUrl.substring(0, mlServiceUrl.length() - 1) 
            : mlServiceUrl;
        this.objectMapper = new ObjectMapper();
        log.info("RemoteMlAdapter initialized with URL: {}", this.mlServiceUrl);
    }
    
    @Override
    public AnomalyScore scoreWindow(String containerId, FeatureVector featureVector) {
        String url = mlServiceUrl + "/api/ml/score";
        
        Map<String, Object> request = new HashMap<>();
        request.put("container_id", containerId);
        request.put("feature_vector", featureVector.values());
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            
            String response = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response);
            
            double score = root.path("score").asDouble(0.0);
            boolean anomalous = root.path("anomalous").asBoolean(false);
            
            log.debug("ML service score for {}: score={}, anomalous={}", containerId, score, anomalous);
            return new AnomalyScore(score, anomalous);
            
        } catch (RestClientException e) {
            log.error("Failed to call ML service for scoring: {}", e.getMessage());
            throw new RuntimeException("ML service unavailable", e);
        } catch (Exception e) {
            log.error("Error parsing ML service response: {}", e.getMessage());
            throw new RuntimeException("ML service response parsing failed", e);
        }
    }
    
    @Override
    public void trainModel(String containerId, List<FeatureVector> trainingData, TrainingContext context) {
        String url = mlServiceUrl + "/api/ml/train";
        
        // Convert feature vectors to list of float lists
        List<List<Float>> data = trainingData.stream()
            .map(FeatureVector::values)
            .collect(Collectors.toList());
        
        Map<String, Object> request = new HashMap<>();
        request.put("container_id", containerId);
        request.put("training_data", data);
        
        if (context != null) {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("reason", context.reason());
            ctx.put("min_records_per_window", context.minRecordsPerWindow());
            request.put("context", ctx);
        }
        
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
            
            String response = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response);
            
            int version = root.path("version").asInt();
            String status = root.path("status").asText();
            
            log.info("ML service trained model for {}: version={}, status={}", 
                containerId, version, status);
            
        } catch (RestClientException e) {
            log.error("Failed to call ML service for training: {}", e.getMessage());
            throw new RuntimeException("ML service unavailable", e);
        } catch (Exception e) {
            log.error("Error parsing ML service response: {}", e.getMessage());
            throw new RuntimeException("ML service response parsing failed", e);
        }
    }
    
    @Override
    public ModelMeta getModelMeta(String containerId) {
        String url = mlServiceUrl + "/api/ml/models/" + containerId;
        
        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            
            String modelId = root.path("model_id").asText();
            int version = root.path("version").asInt();
            String featureVersion = root.path("feature_version").asText("v1");
            String statusStr = root.path("status").asText("UNTRAINED");
            
            ModelStatus status;
            try {
                status = ModelStatus.valueOf(statusStr);
            } catch (IllegalArgumentException e) {
                status = ModelStatus.UNTRAINED;
            }
            
            return new ModelMeta(modelId, containerId, version, featureVersion, status);
            
        } catch (RestClientException e) {
            log.warn("Failed to get model meta from ML service: {}", e.getMessage());
            return new ModelMeta("unset", containerId, 0, "v1", ModelStatus.UNTRAINED);
        } catch (Exception e) {
            log.error("Error parsing ML service response: {}", e.getMessage());
            return new ModelMeta("unset", containerId, 0, "v1", ModelStatus.UNTRAINED);
        }
    }
    
    /**
     * Check if the ML service is available.
     * 
     * @return true if the service responds to health check
     */
    public boolean isAvailable() {
        String url = mlServiceUrl + "/health";
        
        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            return "ok".equals(root.path("status").asText());
        } catch (Exception e) {
            log.debug("ML service health check failed: {}", e.getMessage());
            return false;
        }
    }
}

