package com.deepkernel.core.service;

import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.ModelVersion;
import com.deepkernel.contracts.model.enums.ModelStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelRegistryService {
    private final Map<String, ModelVersion> versions = new ConcurrentHashMap<>();

    public ModelMeta getMeta(String containerId) {
        ModelVersion v = versions.get(containerId);
        if (v == null) {
            return new ModelMeta("unset", containerId, 0, "v1", ModelStatus.UNTRAINED);
        }
        return new ModelMeta(v.modelId(), v.containerId(), v.version(), v.featureVersion(), v.status());
    }

    public void update(String containerId, String modelId, int version, String featureVersion, ModelStatus status) {
        ModelVersion mv = new ModelVersion(modelId, containerId, version, featureVersion, Instant.now(), status, Map.of());
        versions.put(containerId, mv);
    }
}

