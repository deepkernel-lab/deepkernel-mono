package com.deepkernel.core.service;

import com.deepkernel.contracts.model.Container;
import com.deepkernel.contracts.model.enums.ModelStatus;
import com.deepkernel.contracts.model.enums.PolicyStatus;
import com.deepkernel.contracts.model.enums.PolicyType;
import com.deepkernel.contracts.model.enums.TriageStatus;
import com.deepkernel.core.api.dto.UiContainerView;
import com.deepkernel.core.repo.AnomalyWindowRepository;
import com.deepkernel.core.repo.ContainerRepository;
import com.deepkernel.core.repo.PolicyRepository;
import com.deepkernel.core.repo.TriageResultRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContainerViewServiceTest {

    @Test
    void surfacesLatestVerdictScoreAndPolicy() {
        ContainerRepository containerRepository = new ContainerRepository();
        AnomalyWindowRepository windowRepository = new AnomalyWindowRepository();
        PolicyRepository policyRepository = new PolicyRepository();
        TriageResultRepository triageResultRepository = new TriageResultRepository();
        ModelRegistryService modelRegistryService = new ModelRegistryService();

        Container c = new Container("c1", "ns", "node1", "Running", true, ModelStatus.READY);
        containerRepository.upsert(c);

        windowRepository.save(new com.deepkernel.contracts.model.AnomalyWindow(
                "w1", "c1", Instant.now(), Instant.now(), 0.5, true, TriageStatus.THREAT, "t1"
        ));
        policyRepository.save(new com.deepkernel.contracts.model.Policy(
                "p1", "c1", PolicyType.SECCOMP, Map.of(), Instant.now(), "node1", PolicyStatus.APPLIED
        ));
        ContainerViewService svc = new ContainerViewService(
                containerRepository,
                modelRegistryService,
                windowRepository,
                policyRepository,
                triageResultRepository
        );

        UiContainerView view = svc.list().stream().filter(v -> v.id().equals("c1")).findFirst().orElseThrow();
        assertEquals("THREAT", view.lastVerdict());
        assertEquals(0.5, view.lastScore());
        assertEquals("APPLIED", view.policyStatus());
        assertEquals("SECCOMP", view.policyType());
    }
}

