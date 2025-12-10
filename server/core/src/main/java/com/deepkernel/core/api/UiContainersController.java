package com.deepkernel.core.api;

import com.deepkernel.contracts.model.Container;
import com.deepkernel.core.repo.ContainerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ui/containers")
public class UiContainersController {

    private final ContainerRepository containerRepository;

    public UiContainersController(ContainerRepository containerRepository) {
        this.containerRepository = containerRepository;
    }

    @GetMapping
    public ResponseEntity<List<Container>> listContainers() {
        return ResponseEntity.ok(containerRepository.findAll());
    }
}

