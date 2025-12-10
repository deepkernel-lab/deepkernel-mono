package com.deepkernel.cicd;

import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.core.ports.ChangeContextPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Change context adapter that provides deployment/code change information.
 * For demo purposes, this provides mock data. In production, this would
 * integrate with GitHub API or a deployment tracking system.
 */
public class GitHubChangeContextAdapter implements ChangeContextPort {
    private static final Logger log = LoggerFactory.getLogger(GitHubChangeContextAdapter.class);
    
    // In-memory store for demo purposes - simulates deployment tracking
    private final Map<String, ChangeContext> recentDeployments = new ConcurrentHashMap<>();
    
    // Demo deployment window - contexts older than this are not returned
    private static final int DEPLOYMENT_WINDOW_MINUTES = 30;
    
    public GitHubChangeContextAdapter() {
        // Seed with demo data for sample containers
        seedDemoDeployments();
    }
    
    public GitHubChangeContextAdapter(
            @Value("${deepkernel.github.token:}") String token,
            @Value("${deepkernel.github.repo:}") String repo) {
        // Token and repo would be used for real GitHub API integration
        seedDemoDeployments();
    }
    
    @Override
    public ChangeContext getChangeContext(String containerId, Instant since) {
        ChangeContext context = recentDeployments.get(containerId);
        
        if (context == null) {
            log.debug("No deployment context found for container: {}", containerId);
            return null;
        }
        
        // Check if deployment is within the time window
        if (context.deployedAt() != null && since != null) {
            if (context.deployedAt().isBefore(since)) {
                log.debug("Deployment for {} is older than requested window", containerId);
                return null;
            }
        }
        
        // Check if deployment is recent (within deployment window)
        Instant cutoff = Instant.now().minus(DEPLOYMENT_WINDOW_MINUTES, ChronoUnit.MINUTES);
        if (context.deployedAt() != null && context.deployedAt().isBefore(cutoff)) {
            log.debug("Deployment for {} is outside recent window", containerId);
            return null;
        }
        
        log.info("Returning deployment context for {}: commit {}", containerId, context.commitId());
        return context;
    }
    
    /**
     * Register a new deployment. Can be called by a webhook handler or CI/CD integration.
     */
    public void registerDeployment(ChangeContext context) {
        recentDeployments.put(context.containerId(), context);
        log.info("Registered deployment for {}: {}", context.containerId(), context.commitId());
    }
    
    /**
     * Clear a deployment context (e.g., after it's been analyzed).
     */
    public void clearDeployment(String containerId) {
        recentDeployments.remove(containerId);
    }
    
    /**
     * Seeds demo deployment data for demonstration purposes.
     */
    private void seedDemoDeployments() {
        // Demo deployment for billing-api container
        recentDeployments.put("prod/billing-api", new ChangeContext(
            "prod/billing-api",
            "a1b2c3d4e5f6789",
            "https://github.com/acme/billing-service",
            List.of(
                "src/api/payments.ts",
                "src/services/stripe-integration.ts",
                "src/db/transactions.sql"
            ),
            "Added Stripe payment processing integration with webhook handlers",
            Instant.now().minus(15, ChronoUnit.MINUTES)
        ));
        
        // Demo deployment for frontend container
        recentDeployments.put("prod/frontend", new ChangeContext(
            "prod/frontend",
            "f6e5d4c3b2a1098",
            "https://github.com/acme/frontend-app",
            List.of(
                "src/components/CheckoutForm.tsx",
                "src/hooks/usePayment.ts"
            ),
            "Updated checkout flow with new payment form validation",
            Instant.now().minus(10, ChronoUnit.MINUTES)
        ));
        
        // Demo deployment for a backend service
        recentDeployments.put("bachat-backend", new ChangeContext(
            "bachat-backend",
            "deadbeef12345678",
            "https://github.com/deepkernel-lab/bachat-bank",
            List.of(
                "backend/app.py",
                "backend/routes/transactions.py",
                "backend/models.py"
            ),
            "Updated transaction processing with new validation rules",
            Instant.now().minus(5, ChronoUnit.MINUTES)
        ));
        
        log.info("Seeded {} demo deployment contexts", recentDeployments.size());
    }
}
