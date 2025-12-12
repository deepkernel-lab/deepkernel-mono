package com.deepkernel.policy;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.contracts.ports.PolicyGeneratorPort;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.contracts.model.enums.PolicyType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class DefaultPolicyGenerator implements PolicyGeneratorPort {
    @Override
    public Policy generatePolicy(AnomalyWindow window, TriageResult triageResult) {
        if (!"THREAT".equalsIgnoreCase(triageResult.verdict())) {
            return null;
        }

        // Prefer LLM-proposed policy if present in triageResult.llmResponseRaw (JSON).
        Map<String, Object> spec = null;
        try {
            if (triageResult.llmResponseRaw() != null && !triageResult.llmResponseRaw().isBlank()) {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = om.readTree(triageResult.llmResponseRaw());
                com.fasterxml.jackson.databind.JsonNode policyNode = root.path("policy");
                if (!policyNode.isMissingNode() && !policyNode.isNull()) {
                    com.fasterxml.jackson.databind.JsonNode specNode = policyNode.path("spec");
                    if (!specNode.isMissingNode() && !specNode.isNull()) {
                        spec = om.convertValue(specNode, java.util.Map.class);
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to default demo policy
        }

        if (spec == null) {
            spec = Map.of(
                    "profile_name", "dk-demo-block-connect",
                    "syscalls", java.util.List.of(
                            Map.of(
                                    "name", "connect",
                                    "action", "SCMP_ACT_ERRNO"
                            )
                    )
            );
        }

        return new Policy(
                UUID.randomUUID().toString(),
                window.containerId(),
                PolicyType.SECCOMP,
                spec,
                Instant.now(),
                null,
                PolicyStatus.PENDING
        );
    }
}

