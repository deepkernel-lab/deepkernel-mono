package com.deepkernel.core.service;

import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.contracts.model.LongDumpRequest;
import com.deepkernel.contracts.model.LongDumpComplete;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import com.deepkernel.contracts.ports.AgentControlPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TrainingService {
    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);

    private final AnomalyDetectionPort anomalyDetectionPort;
    private final ModelRegistryService modelRegistryService;
    private final AgentControlPort agentControlPort;

    // Track in-flight training jobs keyed by containerId
    private final Map<String, TrainingJob> jobs = new ConcurrentHashMap<>();

    private final String trainScriptPath;
    private final String mlServiceUrl;

    public TrainingService(AnomalyDetectionPort anomalyDetectionPort,
                           ModelRegistryService modelRegistryService,
                           AgentControlPort agentControlPort,
                           @Value("${deepkernel.ml-service.url:http://localhost:8081}") String mlServiceUrl,
                           @Value("${deepkernel.training.script:ml-service/tools/train_from_dump.py}") String trainScriptPath) {
        this.anomalyDetectionPort = anomalyDetectionPort;
        this.modelRegistryService = modelRegistryService;
        this.agentControlPort = agentControlPort;
        this.trainScriptPath = trainScriptPath;
        this.mlServiceUrl = mlServiceUrl;
    }

    /**
     * Legacy direct train (requires vectors already prepared).
     */
    public void train(String containerId, List<FeatureVector> vectors, TrainingContext context) {
        // Enforce “real” flow semantics:
        // 1) Mark model as TRAINING
        // 2) Attempt training (remote/local). Caller should have prepared data (e.g., 1m dump -> vectors)
        // 3) On success, mark READY; on failure, roll back so UI shows “Create model” again.

        int nextVersion = modelRegistryService.getMeta(containerId).version() + 1;
        modelRegistryService.update(containerId, "model-" + containerId, nextVersion, "v1",
                com.deepkernel.contracts.model.enums.ModelStatus.TRAINING);

        try {
            // If no context provided, use a default hint for a 1-minute dump / minimal records
            TrainingContext effectiveCtx = context != null ? context : new TrainingContext("train-1m-dump", 1);
            anomalyDetectionPort.trainModel(containerId, vectors, effectiveCtx);
            modelRegistryService.setStatus(containerId, nextVersion, com.deepkernel.contracts.model.enums.ModelStatus.READY);
        } catch (Exception e) {
            // Roll back so UI can show the “Create model” option again
            modelRegistryService.removeLatest(containerId);
            throw e;
        }
    }

    /**
     * Orchestrated training: request a long dump from agent, then train when dump completes.
     */
    public void startOrchestratedTraining(String containerId, String agentId, int durationSec, TrainingContext context) {
        if (jobs.containsKey(containerId)) {
            throw new IllegalStateException("Training already in progress for " + containerId);
        }

        int nextVersion = modelRegistryService.getMeta(containerId).version() + 1;
        modelRegistryService.update(containerId, "model-" + containerId, nextVersion, "v1",
                com.deepkernel.contracts.model.enums.ModelStatus.TRAINING);

        TrainingJob job = new TrainingJob(containerId, agentId, nextVersion, durationSec);
        jobs.put(containerId, job);

        try {
            agentControlPort.requestLongDump(agentId, containerId, new LongDumpRequest(durationSec, "baseline-train-1m"));
            log.info("Requested long dump for training: container={} agent={} duration={}s version={}",
                    containerId, agentId, durationSec, nextVersion);
        } catch (Exception e) {
            jobs.remove(containerId);
            modelRegistryService.removeLatest(containerId);
            throw e;
        }
    }

    /**
     * Invoked when agent reports dump completion.
     */
    public void handleDumpComplete(LongDumpComplete completion) {
        TrainingJob job = jobs.get(completion.containerId());
        if (job == null) {
            return; // Not a training-triggered dump; ignore.
        }

        // Run training asynchronously to avoid blocking HTTP handler
        new Thread(() -> runTrainingPipeline(job, completion)).start();
    }

    private void runTrainingPipeline(TrainingJob job, LongDumpComplete completion) {
        String containerId = job.containerId();
        try {
            ensureDumpExists(completion.dumpPath());
            runTrainScript(completion.dumpPath(), containerId);
            modelRegistryService.setStatus(containerId, job.version(), com.deepkernel.contracts.model.enums.ModelStatus.READY);
            log.info("Training completed for {} version {}", containerId, job.version());
        } catch (Exception e) {
            log.warn("Training failed for {}: {}", containerId, e.getMessage());
            modelRegistryService.removeLatest(containerId);
        } finally {
            jobs.remove(containerId);
        }
    }

    private void ensureDumpExists(String path) {
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            throw new IllegalStateException("Dump file not found: " + path);
        }
    }

    private void runTrainScript(String dumpPath, String containerId) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "python3",
                trainScriptPath,
                dumpPath,
                "--container-id", containerId,
                "--ml-service-url", mlServiceUrl
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("Training script failed (rc=" + rc + ")");
        }
    }

    private record TrainingJob(String containerId, String agentId, int version, int durationSec) {}
}

