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
        Map<String, Object> spec = Map.of(
                "type", "SECCOMP",
                "profile_name", "dk-deny-inet-high-ports",
                "syscalls", java.util.List.of(
                        Map.of(
                                "name", "connect",
                                "action", "SCMP_ACT_ERRNO",
                                "args", java.util.List.of(Map.of("index", 1, "op", "SCMP_CMP_GE", "value", 1024))
                        )
                )
        );
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

