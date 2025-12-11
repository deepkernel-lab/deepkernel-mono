package com.deepkernel.core.service;

import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.ModelVersion;
import com.deepkernel.contracts.model.enums.ModelStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

@Service
public class ModelRegistryService {
    private final Map<String, List<ModelVersion>> versions = new ConcurrentHashMap<>();

    public ModelMeta getMeta(String containerId) {
        List<ModelVersion> list = versions.get(containerId);
        if (list == null || list.isEmpty()) {
            return new ModelMeta("unset", containerId, 0, "v1", ModelStatus.UNTRAINED);
        }
        ModelVersion v = list.get(list.size() - 1);
        return new ModelMeta(v.modelId(), v.containerId(), v.version(), v.featureVersion(), v.status());
    }

    public void update(String containerId, String modelId, int version, String featureVersion, ModelStatus status) {
        ModelVersion mv = new ModelVersion(modelId, containerId, version, featureVersion, Instant.now(), status, Map.of());
        versions.computeIfAbsent(containerId, k -> new CopyOnWriteArrayList<>()).add(mv);
    }

    public List<ModelVersion> list(String containerId) {
        return versions.getOrDefault(containerId, List.of());
    }
}

