package com.deepkernel.anomaly.engine;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.core.ports.AnomalyDetectionPort;

import java.util.List;

public class InProcessIsolationForestAdapter implements AnomalyDetectionPort {
    @Override
    public AnomalyScore scoreWindow(String containerId, FeatureVector featureVector) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void trainModel(String containerId, List<FeatureVector> trainingData, TrainingContext context) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public ModelMeta getModelMeta(String containerId) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}

