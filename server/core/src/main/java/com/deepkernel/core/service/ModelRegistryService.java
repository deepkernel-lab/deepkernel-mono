package com.deepkernel.core.service;

import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.ModelVersion;
import com.deepkernel.contracts.model.enums.ModelStatus;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

@Service
public class ModelRegistryService {
    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);
    
    private final Map<String, List<ModelVersion>> versions = new ConcurrentHashMap<>();
    
    // Lazy injection to avoid circular dependency
    private AnomalyDetectionPort anomalyDetectionPort;
    
    @Autowired
    public void setAnomalyDetectionPort(AnomalyDetectionPort anomalyDetectionPort) {
        this.anomalyDetectionPort = anomalyDetectionPort;
    }

    public ModelMeta getMeta(String containerId) {
        // First check local cache
        List<ModelVersion> list = versions.get(containerId);
        if (list != null && !list.isEmpty()) {
            ModelVersion v = list.get(list.size() - 1);
            return new ModelMeta(v.modelId(), v.containerId(), v.version(), v.featureVersion(), v.status());
        }
        
        // If not in cache, try to query ML service
        if (anomalyDetectionPort != null) {
            try {
                ModelMeta remoteMeta = anomalyDetectionPort.getModelMeta(containerId);
                if (remoteMeta != null && remoteMeta.status() != ModelStatus.UNTRAINED) {
                    // Cache the result
                    update(containerId, remoteMeta.modelId(), remoteMeta.version(), 
                           remoteMeta.featureVersion(), remoteMeta.status());
                    log.debug("Synced model status from ML service for {}: {}", containerId, remoteMeta.status());
                    return remoteMeta;
                }
            } catch (Exception e) {
                log.debug("Could not query ML service for model meta: {}", e.getMessage());
            }
        }
        
        return new ModelMeta("unset", containerId, 0, "v1", ModelStatus.UNTRAINED);
    }

    public void update(String containerId, String modelId, int version, String featureVersion, ModelStatus status) {
        ModelVersion mv = new ModelVersion(modelId, containerId, version, featureVersion, Instant.now(), status, Map.of());
        versions.computeIfAbsent(containerId, k -> new CopyOnWriteArrayList<>()).add(mv);
        log.info("Updated model registry for {}: version={}, status={}", containerId, version, status);
    }
    
    /**
     * Sync model status from ML service for all known containers.
     */
    public int syncFromMlService(List<String> containerIds) {
        if (anomalyDetectionPort == null) {
            log.warn("Cannot sync: anomalyDetectionPort not available");
            return 0;
        }
        
        int synced = 0;
        for (String containerId : containerIds) {
            try {
                ModelMeta meta = anomalyDetectionPort.getModelMeta(containerId);
                if (meta != null && meta.status() != ModelStatus.UNTRAINED) {
                    update(containerId, meta.modelId(), meta.version(), meta.featureVersion(), meta.status());
                    synced++;
                }
            } catch (Exception e) {
                log.debug("Failed to sync model for {}: {}", containerId, e.getMessage());
            }
        }
        log.info("Synced {} models from ML service", synced);
        return synced;
    }

    public List<ModelVersion> list(String containerId) {
        return versions.getOrDefault(containerId, List.of());
    }
    
    /**
     * Clear local cache (useful for testing/admin).
     */
    public void clearCache() {
        versions.clear();
    }
}

