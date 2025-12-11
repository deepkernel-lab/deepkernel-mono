package com.deepkernel.anomaly.engine;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ModelMeta;
import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.contracts.ports.AnomalyDetectionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hybrid anomaly detection adapter that tries the remote ML service first,
 * then falls back to the local in-process adapter if the remote service is unavailable.
 * 
 * Features:
 * - Automatic fallback to local adapter
 * - Periodic health checks to detect when remote becomes available
 * - Configurable via environment variables
 */
public class HybridAnomalyAdapter implements AnomalyDetectionPort {
    private static final Logger log = LoggerFactory.getLogger(HybridAnomalyAdapter.class);
    
    // Health check interval (5 minutes)
    private static final long HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1000;
    
    private final RemoteMlAdapter remoteAdapter;
    private final InProcessIsolationForestAdapter localAdapter;
    private final boolean preferRemote;
    
    private final AtomicBoolean remoteAvailable = new AtomicBoolean(false);
    private final AtomicLong lastHealthCheck = new AtomicLong(0);
    
    /**
     * Create a hybrid adapter with both remote and local adapters.
     * 
     * @param remoteAdapter The remote ML service adapter
     * @param localAdapter The local in-process adapter
     * @param preferRemote Whether to prefer remote (true) or always use local (false)
     */
    public HybridAnomalyAdapter(
            RemoteMlAdapter remoteAdapter,
            InProcessIsolationForestAdapter localAdapter,
            boolean preferRemote) {
        this.remoteAdapter = remoteAdapter;
        this.localAdapter = localAdapter;
        this.preferRemote = preferRemote;
        
        // Initial health check
        if (preferRemote) {
            checkRemoteHealth();
        }
        
        log.info("HybridAnomalyAdapter initialized: preferRemote={}, remoteAvailable={}", 
            preferRemote, remoteAvailable.get());
    }
    
    @Override
    public AnomalyScore scoreWindow(String containerId, FeatureVector featureVector) {
        if (shouldUseRemote()) {
            try {
                AnomalyScore score = remoteAdapter.scoreWindow(containerId, featureVector);
                log.debug("Used remote ML service for scoring {}", containerId);
                return score;
            } catch (Exception e) {
                log.warn("Remote ML service failed, falling back to local: {}", e.getMessage());
                remoteAvailable.set(false);
            }
        }
        
        log.debug("Using local adapter for scoring {}", containerId);
        return localAdapter.scoreWindow(containerId, featureVector);
    }
    
    @Override
    public void trainModel(String containerId, List<FeatureVector> trainingData, TrainingContext context) {
        // Always train both local and remote if available
        // This ensures local adapter has a fallback model
        
        // Train local first (guaranteed to work)
        try {
            localAdapter.trainModel(containerId, trainingData, context);
            log.info("Trained local model for {}", containerId);
        } catch (Exception e) {
            log.error("Failed to train local model for {}: {}", containerId, e.getMessage());
        }
        
        // Try to train remote if available
        if (shouldUseRemote()) {
            try {
                remoteAdapter.trainModel(containerId, trainingData, context);
                log.info("Trained remote model for {}", containerId);
            } catch (Exception e) {
                log.warn("Failed to train remote model for {}: {}", containerId, e.getMessage());
                remoteAvailable.set(false);
            }
        }
    }
    
    @Override
    public ModelMeta getModelMeta(String containerId) {
        if (shouldUseRemote()) {
            try {
                return remoteAdapter.getModelMeta(containerId);
            } catch (Exception e) {
                log.warn("Failed to get model meta from remote: {}", e.getMessage());
                remoteAvailable.set(false);
            }
        }
        
        return localAdapter.getModelMeta(containerId);
    }
    
    /**
     * Determine if we should try to use the remote adapter.
     */
    private boolean shouldUseRemote() {
        if (!preferRemote) {
            return false;
        }
        
        // Periodic health check
        long now = System.currentTimeMillis();
        if (now - lastHealthCheck.get() > HEALTH_CHECK_INTERVAL_MS) {
            checkRemoteHealth();
        }
        
        return remoteAvailable.get();
    }
    
    /**
     * Check if the remote ML service is available.
     */
    private void checkRemoteHealth() {
        lastHealthCheck.set(System.currentTimeMillis());
        
        try {
            boolean available = remoteAdapter.isAvailable();
            boolean wasAvailable = remoteAvailable.getAndSet(available);
            
            if (available && !wasAvailable) {
                log.info("Remote ML service is now available");
            } else if (!available && wasAvailable) {
                log.warn("Remote ML service is no longer available, using local fallback");
            }
        } catch (Exception e) {
            remoteAvailable.set(false);
            log.debug("Remote ML service health check failed: {}", e.getMessage());
        }
    }
    
    /**
     * Force a health check and update availability status.
     */
    public void refreshRemoteStatus() {
        checkRemoteHealth();
    }
    
    /**
     * Get the current mode of operation.
     * 
     * @return "REMOTE" if using ML service, "LOCAL" if using in-process adapter
     */
    public String getCurrentMode() {
        return (preferRemote && remoteAvailable.get()) ? "REMOTE" : "LOCAL";
    }
    
    /**
     * Check if the remote ML service is currently available.
     */
    public boolean isRemoteAvailable() {
        return remoteAvailable.get();
    }
}

