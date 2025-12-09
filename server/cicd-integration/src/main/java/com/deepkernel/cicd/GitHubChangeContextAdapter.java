package com.deepkernel.cicd;

import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.core.ports.ChangeContextPort;

import java.time.Instant;

public class GitHubChangeContextAdapter implements ChangeContextPort {
    @Override
    public ChangeContext getChangeContext(String containerId, Instant since) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}

