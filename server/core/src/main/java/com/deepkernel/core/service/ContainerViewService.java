package com.deepkernel.core.service;

import com.deepkernel.contracts.model.Container;
import com.deepkernel.contracts.model.enums.ModelStatus;
import com.deepkernel.core.api.dto.UiContainerView;
import com.deepkernel.core.repo.ContainerRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ContainerViewService {
    private final ContainerRepository repository;
    private final ModelRegistryService modelRegistryService;

    public ContainerViewService(ContainerRepository repository, ModelRegistryService modelRegistryService) {
        this.repository = repository;
        this.modelRegistryService = modelRegistryService;
    }

    public List<UiContainerView> list() {
        return repository.findAll().stream().map(this::map).collect(Collectors.toList());
    }

    private UiContainerView map(Container c) {
        var meta = modelRegistryService.getMeta(c.id());
        ModelStatus status = meta != null ? meta.status() : c.modelStatus();
        return new UiContainerView(
                c.id(),
                c.namespace(),
                c.node(),
                c.status(),
                c.agentConnected(),
                status,
                null,
                null,
                null
        );
    }
}

