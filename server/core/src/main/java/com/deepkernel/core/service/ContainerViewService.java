package com.deepkernel.core.service;

import com.deepkernel.contracts.model.Container;
import com.deepkernel.contracts.model.Policy;
import com.deepkernel.contracts.model.enums.ModelStatus;
import com.deepkernel.contracts.model.enums.TriageStatus;
import com.deepkernel.core.api.dto.UiContainerView;
import com.deepkernel.core.repo.AnomalyWindowRepository;
import com.deepkernel.core.repo.ContainerRepository;
import com.deepkernel.core.repo.PolicyRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ContainerViewService {
    private final ContainerRepository repository;
    private final ModelRegistryService modelRegistryService;
    private final AnomalyWindowRepository windowRepository;
    private final PolicyRepository policyRepository;

    public ContainerViewService(ContainerRepository repository,
                                ModelRegistryService modelRegistryService,
                                AnomalyWindowRepository windowRepository,
                                PolicyRepository policyRepository) {
        this.repository = repository;
        this.modelRegistryService = modelRegistryService;
        this.windowRepository = windowRepository;
        this.policyRepository = policyRepository;
    }

    public List<UiContainerView> list() {
        return repository.findAll().stream().map(this::map).collect(Collectors.toList());
    }

    private UiContainerView map(Container c) {
        var meta = modelRegistryService.getMeta(c.id());
        ModelStatus status = meta != null ? meta.status() : c.modelStatus();
        var latestWindow = windowRepository.latest(c.id());
        var latestPolicy = policyRepository.latest(c.id());
        String verdict = latestWindow != null && latestWindow.triageStatus() != null
                ? latestWindow.triageStatus().name()
                : null;
        Double score = latestWindow != null ? latestWindow.mlScore() : null;
        String deploy = null; // placeholder for deploy info
        String policyStatus = latestPolicy != null && latestPolicy.status() != null ? latestPolicy.status().name() : null;
        String policyType = latestPolicy != null && latestPolicy.type() != null ? latestPolicy.type().name() : null;
        return new UiContainerView(
                c.id(),
                c.namespace(),
                c.node(),
                c.status(),
                c.agentConnected(),
                status,
                verdict,
                score,
                deploy,
                policyStatus,
                policyType
        );
    }
}

