package com.deepkernel.policy;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.core.ports.PolicyGeneratorPort;

public class DefaultPolicyGenerator implements PolicyGeneratorPort {
    @Override
    public Policy generatePolicy(AnomalyWindow window, TriageResult triageResult) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}

