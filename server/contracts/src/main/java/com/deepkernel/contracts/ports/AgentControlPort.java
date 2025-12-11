package com.deepkernel.contracts.ports;

import com.deepkernel.contracts.model.LongDumpComplete;
import com.deepkernel.contracts.model.LongDumpRequest;
import com.deepkernel.contracts.model.Policy;

public interface AgentControlPort {
    void requestLongDump(String agentId, String containerId, LongDumpRequest request);

    void notifyLongDumpComplete(LongDumpComplete completion);

    void applyPolicy(String agentId, String containerId, Policy policy);
}

