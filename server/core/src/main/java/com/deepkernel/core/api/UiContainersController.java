package com.deepkernel.core.api;

import com.deepkernel.contracts.model.ModelVersion;
import com.deepkernel.core.api.dto.UiContainerView;
import com.deepkernel.core.service.ContainerViewService;
import com.deepkernel.core.service.ModelRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ui/containers")
public class UiContainersController {

    private final ContainerViewService containerViewService;
    private final ModelRegistryService modelRegistryService;

    public UiContainersController(ContainerViewService containerViewService, ModelRegistryService modelRegistryService) {
        this.containerViewService = containerViewService;
        this.modelRegistryService = modelRegistryService;
    }

    @GetMapping
    public ResponseEntity<List<UiContainerView>> listContainers() {
        return ResponseEntity.ok(containerViewService.list());
    }

    @GetMapping("/{id}/models")
    public ResponseEntity<List<ModelVersion>> listModels(@PathVariable("id") String id) {
        return ResponseEntity.ok(modelRegistryService.list(id));
    }
}

