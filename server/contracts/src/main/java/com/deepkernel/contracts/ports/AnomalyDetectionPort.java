package com.deepkernel.contracts.ports;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.TrainingContext;

import java.util.List;

public interface AnomalyDetectionPort {
    AnomalyScore scoreWindow(String containerId, FeatureVector featureVector);

    void trainModel(String containerId, List<FeatureVector> trainingData, TrainingContext context);

    ModelMeta getModelMeta(String containerId);
}

