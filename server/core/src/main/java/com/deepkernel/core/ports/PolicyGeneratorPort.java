package com.deepkernel.core.ports;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.TriageResult;

public interface PolicyGeneratorPort {
    Policy generatePolicy(AnomalyWindow window, TriageResult triageResult);
}

