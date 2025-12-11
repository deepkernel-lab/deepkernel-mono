package com.deepkernel.contracts.ports;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.model.TriageResult;

public interface TriagePort {
    TriageResult triage(AnomalyWindow window, ChangeContext changeContext);
}

