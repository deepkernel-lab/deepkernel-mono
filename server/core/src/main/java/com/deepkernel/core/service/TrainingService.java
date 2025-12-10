package com.deepkernel.core.service;

import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.core.ports.AnomalyDetectionPort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TrainingService {
    private final AnomalyDetectionPort anomalyDetectionPort;
    private final ModelRegistryService modelRegistryService;

    public TrainingService(AnomalyDetectionPort anomalyDetectionPort, ModelRegistryService modelRegistryService) {
        this.anomalyDetectionPort = anomalyDetectionPort;
        this.modelRegistryService = modelRegistryService;
    }

    public void train(String containerId, List<FeatureVector> vectors, TrainingContext context) {
        anomalyDetectionPort.trainModel(containerId, vectors, context);
        // Update registry optimistically
        modelRegistryService.update(containerId, "model-" + containerId, context != null ? 1 : 1, "v1",
                com.deepkernel.contracts.model.enums.ModelStatus.READY);
    }
}

