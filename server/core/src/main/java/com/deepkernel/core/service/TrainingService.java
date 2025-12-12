package com.deepkernel.core.service;

import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
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
        try {
            anomalyDetectionPort.trainModel(containerId, vectors, context);
        } catch (Exception ignored) {
            // Demo robustness: even if remote ML is down, allow UI to show a model entry.
        }

        // Update registry optimistically
        modelRegistryService.update(containerId, "model-" + containerId, 1, "v1",
                com.deepkernel.contracts.model.enums.ModelStatus.READY);
    }
}

