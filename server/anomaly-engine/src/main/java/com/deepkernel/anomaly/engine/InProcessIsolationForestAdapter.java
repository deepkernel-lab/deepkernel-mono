package com.deepkernel.anomaly.engine;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.core.ports.AnomalyDetectionPort;

import java.util.List;

public class InProcessIsolationForestAdapter implements AnomalyDetectionPort {
    private final Map<String, ModelMeta> models = new HashMap<>();

    @Override
    public AnomalyScore scoreWindow(String containerId, FeatureVector featureVector) {
        // Placeholder scoring: deterministic pseudo-score based on size.
        double score = featureVector.values() == null ? 0.0 : -(featureVector.values().size() % 10) / 10.0;
        boolean anomalous = score < -0.7;
        return new AnomalyScore(score, anomalous);
    }

    @Override
    public void trainModel(String containerId, List<FeatureVector> trainingData, TrainingContext context) {
        int version = models.getOrDefault(containerId, new ModelMeta("unset", containerId, 0, "v1", com.deepkernel.contracts.model.enums.ModelStatus.UNTRAINED)).version() + 1;
        ModelMeta meta = new ModelMeta("model-" + containerId, containerId, version, context != null ? "v1" : "v1", com.deepkernel.contracts.model.enums.ModelStatus.READY);
        models.put(containerId, meta);
    }

    @Override
    public ModelMeta getModelMeta(String containerId) {
        return models.get(containerId);
    }
}

