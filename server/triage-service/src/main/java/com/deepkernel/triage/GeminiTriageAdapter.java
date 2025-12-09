package com.deepkernel.triage;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.core.ports.TriagePort;

public class GeminiTriageAdapter implements TriagePort {
    @Override
    public TriageResult triage(AnomalyWindow window, ChangeContext changeContext) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}

