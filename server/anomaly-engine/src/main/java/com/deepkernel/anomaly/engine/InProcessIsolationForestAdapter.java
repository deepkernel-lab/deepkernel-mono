package com.deepkernel.anomaly.engine;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.contracts.model.enums.ModelStatus;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process anomaly detection adapter.
 * Uses heuristic-based scoring for demo purposes.
 * 
 * TODO: Replace with real Isolation Forest implementation or
 * delegate to ml-service for production use.
 */
public class InProcessIsolationForestAdapter implements AnomalyDetectionPort {
    private static final Logger log = LoggerFactory.getLogger(InProcessIsolationForestAdapter.class);
    
    // Feature vector indices (from MASTER_PLAN.md Section 7)
    private static final int MARKOV_MATRIX_SIZE = 24 * 24; // K*K = 576
    private static final int IDX_UNIQUE_TWO_GRAMS = 576;
    private static final int IDX_ENTROPY = 577;
    private static final int IDX_FILE_RATIO = 578;
    private static final int IDX_NET_RATIO = 579;
    private static final int IDX_DURATION = 580;
    private static final int IDX_MEAN_INTER_ARRIVAL = 581;
    
    // Syscall alphabet indices for specific patterns
    private static final int SYSCALL_EXECVE = 0;
    private static final int SYSCALL_OPEN = 1;
    private static final int SYSCALL_CONNECT = 6;
    private static final int SYSCALL_SOCKET = 10;
    private static final int SYSCALL_FORK_CLONE = 11;
    private static final int SYSCALL_MMAP = 12;
    private static final int SYSCALL_PTRACE = 18;
    private static final int SYSCALL_MOUNT = 19;
    
    // Thresholds for heuristic scoring
    private static final double ANOMALY_THRESHOLD = 0.6;
    private static final double HIGH_NET_RATIO_THRESHOLD = 0.4;
    private static final double LOW_ENTROPY_THRESHOLD = 1.0;
    private static final double HIGH_ENTROPY_THRESHOLD = 4.0;
    
    private final Map<String, ModelMeta> models = new ConcurrentHashMap<>();
    private final Map<String, BaselineStats> baselines = new ConcurrentHashMap<>();

    @Override
    public AnomalyScore scoreWindow(String containerId, FeatureVector featureVector) {
        List<Float> values = featureVector.values();
        
        if (values == null || values.isEmpty()) {
            log.debug("Empty feature vector for {}, returning neutral score", containerId);
            return new AnomalyScore(0.0, false);
        }
        
        double score = calculateHeuristicScore(containerId, values);
        boolean anomalous = score > ANOMALY_THRESHOLD;
        
        log.debug("Scored window for {}: score={:.3f}, anomalous={}", containerId, score, anomalous);
        return new AnomalyScore(score, anomalous);
    }

    @Override
    public void trainModel(String containerId, List<FeatureVector> trainingData, TrainingContext context) {
        int currentVersion = models.getOrDefault(containerId, 
            new ModelMeta("unset", containerId, 0, "v1", ModelStatus.UNTRAINED)).version();
        int version = currentVersion + 1;
        
        // Update baseline stats from training data
        if (trainingData != null && !trainingData.isEmpty()) {
            BaselineStats stats = computeBaseline(trainingData);
            baselines.put(containerId, stats);
            log.info("Updated baseline for {} from {} training samples", containerId, trainingData.size());
        }
        
        ModelMeta meta = new ModelMeta("model-" + containerId, containerId, version, "v1", ModelStatus.READY);
        models.put(containerId, meta);
        
        log.info("Model trained for {}: version={}", containerId, version);
    }

    @Override
    public ModelMeta getModelMeta(String containerId) {
        return models.get(containerId);
    }
    
    /**
     * Calculates a heuristic anomaly score based on feature vector patterns.
     * Score ranges from 0.0 (normal) to 1.0 (highly anomalous).
     */
    private double calculateHeuristicScore(String containerId, List<Float> values) {
        double score = 0.0;
        int signals = 0;
        
        // Check for suspicious syscall patterns in Markov matrix
        score += checkSuspiciousSyscallPatterns(values) * 0.3;
        signals++;
        
        // Check network activity ratio
        if (values.size() > IDX_NET_RATIO) {
            float netRatio = values.get(IDX_NET_RATIO);
            if (netRatio > HIGH_NET_RATIO_THRESHOLD) {
                score += (netRatio - HIGH_NET_RATIO_THRESHOLD) / (1.0 - HIGH_NET_RATIO_THRESHOLD) * 0.2;
            }
            signals++;
        }
        
        // Check entropy - both too low and too high can be suspicious
        if (values.size() > IDX_ENTROPY) {
            float entropy = values.get(IDX_ENTROPY);
            if (entropy < LOW_ENTROPY_THRESHOLD) {
                // Very repetitive behavior - could be scripted attack
                score += (LOW_ENTROPY_THRESHOLD - entropy) / LOW_ENTROPY_THRESHOLD * 0.15;
            } else if (entropy > HIGH_ENTROPY_THRESHOLD) {
                // Very chaotic behavior - could be scanning/probing
                score += Math.min((entropy - HIGH_ENTROPY_THRESHOLD) / 2.0, 0.15);
            }
            signals++;
        }
        
        // Check unique syscall transitions
        if (values.size() > IDX_UNIQUE_TWO_GRAMS) {
            float uniqueTwoGrams = values.get(IDX_UNIQUE_TWO_GRAMS);
            // Unusually high diversity can indicate reconnaissance
            if (uniqueTwoGrams > 50) {
                score += Math.min((uniqueTwoGrams - 50) / 100.0, 0.15);
            }
            signals++;
        }
        
        // Compare against baseline if available
        BaselineStats baseline = baselines.get(containerId);
        if (baseline != null) {
            score += computeBaselineDeviation(values, baseline) * 0.2;
            signals++;
        }
        
        // Normalize score
        return Math.min(1.0, score);
    }
    
    /**
     * Checks for suspicious syscall transition patterns in the Markov matrix.
     */
    private double checkSuspiciousSyscallPatterns(List<Float> values) {
        double suspicionScore = 0.0;
        
        if (values.size() < MARKOV_MATRIX_SIZE) {
            return 0.0;
        }
        
        // Check for execve followed by network operations (potential reverse shell)
        float execveToConnect = getMarkovCell(values, SYSCALL_EXECVE, SYSCALL_CONNECT);
        float execveToSocket = getMarkovCell(values, SYSCALL_EXECVE, SYSCALL_SOCKET);
        if (execveToConnect > 0.1 || execveToSocket > 0.1) {
            suspicionScore += 0.3;
        }
        
        // Check for ptrace usage (debugging/injection)
        float anyToPtrace = 0;
        for (int i = 0; i < 24; i++) {
            anyToPtrace += getMarkovCell(values, i, SYSCALL_PTRACE);
        }
        if (anyToPtrace > 0.01) {
            suspicionScore += 0.4;
        }
        
        // Check for mount operations (privilege escalation attempts)
        float anyToMount = 0;
        for (int i = 0; i < 24; i++) {
            anyToMount += getMarkovCell(values, i, SYSCALL_MOUNT);
        }
        if (anyToMount > 0.01) {
            suspicionScore += 0.3;
        }
        
        // Check for fork-bomb patterns (many fork/clone operations)
        float forkToFork = getMarkovCell(values, SYSCALL_FORK_CLONE, SYSCALL_FORK_CLONE);
        if (forkToFork > 0.3) {
            suspicionScore += 0.4;
        }
        
        return Math.min(1.0, suspicionScore);
    }
    
    private float getMarkovCell(List<Float> values, int from, int to) {
        int idx = from * 24 + to;
        if (idx < 0 || idx >= values.size()) {
            return 0.0f;
        }
        return values.get(idx);
    }
    
    /**
     * Computes baseline statistics from training data.
     */
    private BaselineStats computeBaseline(List<FeatureVector> trainingData) {
        double sumNetRatio = 0, sumEntropy = 0, sumUnique = 0;
        int count = 0;
        
        for (FeatureVector fv : trainingData) {
            List<Float> v = fv.values();
            if (v != null && v.size() > IDX_MEAN_INTER_ARRIVAL) {
                sumNetRatio += v.get(IDX_NET_RATIO);
                sumEntropy += v.get(IDX_ENTROPY);
                sumUnique += v.get(IDX_UNIQUE_TWO_GRAMS);
                count++;
            }
        }
        
        if (count == 0) {
            return new BaselineStats(0.1, 2.0, 20.0);
        }
        
        return new BaselineStats(
            sumNetRatio / count,
            sumEntropy / count,
            sumUnique / count
        );
    }
    
    /**
     * Computes deviation from baseline statistics.
     */
    private double computeBaselineDeviation(List<Float> values, BaselineStats baseline) {
        double deviation = 0.0;
        
        if (values.size() > IDX_NET_RATIO) {
            double netDiff = Math.abs(values.get(IDX_NET_RATIO) - baseline.avgNetRatio);
            deviation += netDiff * 0.3;
        }
        
        if (values.size() > IDX_ENTROPY) {
            double entropyDiff = Math.abs(values.get(IDX_ENTROPY) - baseline.avgEntropy);
            deviation += (entropyDiff / 4.0) * 0.4;
        }
        
        if (values.size() > IDX_UNIQUE_TWO_GRAMS) {
            double uniqueDiff = Math.abs(values.get(IDX_UNIQUE_TWO_GRAMS) - baseline.avgUniqueTwoGrams);
            deviation += (uniqueDiff / 50.0) * 0.3;
        }
        
        return Math.min(1.0, deviation);
    }
    
    /**
     * Simple record to store baseline statistics per container.
     */
    private record BaselineStats(double avgNetRatio, double avgEntropy, double avgUniqueTwoGrams) {}
}
