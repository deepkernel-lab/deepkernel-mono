package com.deepkernel.core.api;

import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.contracts.model.enums.PolicyType;
import com.deepkernel.core.repo.PolicyRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API endpoints for viewing and managing policies.
 */
@RestController
@RequestMapping("/api")
public class PolicyController {
    
    private final PolicyRepository policyRepository;
    
    public PolicyController(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }
    
    /**
     * Get all policies for a container.
     * 
     * GET /api/ui/containers/{containerId}/policies
     */
    @GetMapping("/ui/containers/{containerId}/policies")
    public ResponseEntity<List<Policy>> getPoliciesForContainer(@PathVariable String containerId) {
        return ResponseEntity.ok(policyRepository.findByContainer(containerId));
    }
    
    /**
     * Get all policies across all containers.
     * 
     * GET /api/ui/policies
     */
    @GetMapping("/ui/policies")
    public ResponseEntity<List<Policy>> getAllPolicies() {
        return ResponseEntity.ok(policyRepository.findAll());
    }
    
    /**
     * Create a demo/synthetic policy for testing the UI.
     * 
     * POST /api/demo/policies
     * {
     *   "containerId": "bachat-bank_backend_1",
     *   "type": "SECCOMP",
     *   "status": "PENDING"
     * }
     */
    @PostMapping("/demo/policies")
    public ResponseEntity<Policy> createDemoPolicy(@RequestBody Map<String, String> request) {
        String containerId = request.getOrDefault("containerId", "demo-container");
        String typeStr = request.getOrDefault("type", "SECCOMP");
        String statusStr = request.getOrDefault("status", "PENDING");
        
        PolicyType type;
        try {
            type = PolicyType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            type = PolicyType.SECCOMP;
        }
        
        PolicyStatus status;
        try {
            status = PolicyStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            status = PolicyStatus.PENDING;
        }
        
        Map<String, Object> spec = new HashMap<>();
        spec.put("action", "SCMP_ACT_ERRNO");
        spec.put("syscalls", List.of("connect"));
        spec.put("reason", "Anomalous connect() calls detected - possible data exfiltration");
        spec.put("profilePath", "/var/lib/deepkernel/policies/policy-" + containerId + ".json");
        
        Policy policy = new Policy(
            "policy-" + System.currentTimeMillis(),
            containerId,
            type,
            spec,
            Instant.now(),
            "node-1",
            status
        );
        
        policyRepository.save(policy);
        return ResponseEntity.ok(policy);
    }
    
    /**
     * Update policy status (PENDING -> APPLIED).
     * 
     * PUT /api/demo/policies/{policyId}/status
     * { "status": "APPLIED" }
     */
    @PutMapping("/demo/policies/{policyId}/status")
    public ResponseEntity<Map<String, String>> updatePolicyStatus(
            @PathVariable String policyId,
            @RequestBody Map<String, String> request) {
        String newStatus = request.getOrDefault("status", "APPLIED");
        // Note: In a real implementation, we'd update the policy in the repository
        // For demo, this just returns success
        return ResponseEntity.ok(Map.of(
            "policyId", policyId,
            "status", newStatus,
            "message", "Policy status updated"
        ));
    }
}

