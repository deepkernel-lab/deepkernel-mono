package com.deepkernel.core.service;

import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.contracts.model.LongDumpRequest;
import com.deepkernel.contracts.model.LongDumpComplete;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import com.deepkernel.contracts.ports.AgentControlPort;
import com.deepkernel.contracts.model.enums.ModelStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TrainingService {
    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);

    private final AnomalyDetectionPort anomalyDetectionPort;
    private final ModelRegistryService modelRegistryService;
    private final AgentControlPort agentControlPort;

    // Track in-flight training jobs: containerId -> version
    private final Map<String, Integer> jobs = new ConcurrentHashMap<>();

    public TrainingService(AnomalyDetectionPort anomalyDetectionPort,
                           ModelRegistryService modelRegistryService,
                           AgentControlPort agentControlPort) {
        this.anomalyDetectionPort = anomalyDetectionPort;
        this.modelRegistryService = modelRegistryService;
        this.agentControlPort = agentControlPort;
    }

    /**
     * Legacy direct train (when vectors are already available).
     */
    public void train(String containerId, List<FeatureVector> vectors, TrainingContext context) {
        try {
            anomalyDetectionPort.trainModel(containerId, vectors, context);
            modelRegistryService.update(containerId, "model-" + containerId, 1, "v1", ModelStatus.READY);
        } catch (Exception e) {
            log.warn("Direct training failed for {}: {}", containerId, e.getMessage());
        }
    }

    /**
     * Orchestrated training: request a dump, then mark TRAINING; READY will be set on dump completion.
     */
    public void startOrchestratedTraining(String containerId, String agentId, int durationSec, TrainingContext context) {
        int nextVersion = modelRegistryService.getMeta(containerId).version() + 1;
        modelRegistryService.update(containerId, "model-" + containerId, nextVersion, "v1", ModelStatus.TRAINING);
        jobs.put(containerId, nextVersion);
        try {
            agentControlPort.requestLongDump(agentId, containerId, new LongDumpRequest(durationSec, "train-1m"));
            log.info("Requested long dump for training: container={} agent={} duration={}s version={}", containerId, agentId, durationSec, nextVersion);
        } catch (Exception e) {
            jobs.remove(containerId);
            modelRegistryService.removeLatest(containerId);
            throw e;
        }
    }

    /**
     * Called when the agent reports long dump completion.
     * For now, we optimistically mark READY; hook real train-from-dump pipeline as needed.
     */
    public void handleDumpComplete(LongDumpComplete completion) {
        Integer version = jobs.remove(completion.containerId());
        if (version == null) {
            return; // not a training-triggered dump
        }
        try {
            modelRegistryService.setStatus(completion.containerId(), version, ModelStatus.READY);
            log.info("Marked model READY after dump completion for {} version {}", completion.containerId(), version);
        } catch (Exception e) {
            log.warn("Failed to finalize training for {}: {}", completion.containerId(), e.getMessage());
            modelRegistryService.removeLatest(completion.containerId());
        }
    }
}

