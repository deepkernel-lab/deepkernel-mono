package com.deepkernel.core.ports;

import com.deepkernel.contracts.model.ChangeContext;

import java.time.Instant;

public interface ChangeContextPort {
    ChangeContext getChangeContext(String containerId, Instant since);
}

