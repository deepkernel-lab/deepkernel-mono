package com.deepkernel.triage;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.model.TriageResult;
import com.deepkernel.core.ports.TriagePort;
import java.util.UUID;

public class GeminiTriageAdapter implements TriagePort {
    @Override
    public TriageResult triage(AnomalyWindow window, ChangeContext changeContext) {
            double risk = Math.max(0.0, Math.min(1.0, Math.abs(window.mlScore())));
            String verdict = window.anomalous() ? "THREAT" : "SAFE";
            String explanation = window.anomalous()
                    ? "Anomalous window flagged; demo stub verdict THREAT."
                    : "Behavior within normal range; demo stub verdict SAFE.";
            return new TriageResult(
                    UUID.randomUUID().toString(),
                    window.containerId(),
                    window.id(),
                    risk,
                    verdict,
                    explanation,
                    null
            );
    }
}

