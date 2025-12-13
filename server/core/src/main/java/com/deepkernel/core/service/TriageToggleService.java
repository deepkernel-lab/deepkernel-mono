package com.deepkernel.core.service;

import java.util.concurrent.atomic.AtomicBoolean;

public class TriageToggleService {
    private final AtomicBoolean enabled;

    public TriageToggleService(boolean defaultEnabled) {
        this.enabled = new AtomicBoolean(defaultEnabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }
}


