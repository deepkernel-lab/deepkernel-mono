package com.deepkernel.core.api;

import com.deepkernel.contracts.model.Container;
import com.deepkernel.core.repo.ContainerRepository;
import com.deepkernel.core.repo.EventRepository;
import com.deepkernel.core.service.ModelRegistryService;
import com.deepkernel.core.service.TriageToggleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admin endpoints for managing DeepKernel server state.
 * 
 * These endpoints allow clearing stale data, useful during development and testing.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ContainerRepository containerRepository;
    private final EventRepository eventRepository;
    private final ModelRegistryService modelRegistryService;
    private final TriageToggleService triageToggleService;

    public AdminController(ContainerRepository containerRepository, 
                          EventRepository eventRepository,
                          ModelRegistryService modelRegistryService,
                          TriageToggleService triageToggleService) {
        this.containerRepository = containerRepository;
        this.eventRepository = eventRepository;
        this.modelRegistryService = modelRegistryService;
        this.triageToggleService = triageToggleService;
    }

    /**
     * Clear all containers from the registry.
     * 
     * POST /api/admin/containers/clear
     */
    @PostMapping("/containers/clear")
    public ResponseEntity<Map<String, Object>> clearAllContainers() {
        int count = containerRepository.clearAll();
        return ResponseEntity.ok(Map.of(
            "status", "cleared",
            "containersRemoved", count
        ));
    }

    /**
     * Delete containers matching a regex pattern.
     * 
     * DELETE /api/admin/containers?pattern=host-.*
     */
    @DeleteMapping("/containers")
    public ResponseEntity<Map<String, Object>> deleteContainersByPattern(
            @RequestParam("pattern") String pattern) {
        int count = containerRepository.deleteByPattern(pattern);
        return ResponseEntity.ok(Map.of(
            "status", "deleted",
            "pattern", pattern,
            "containersRemoved", count
        ));
    }

    /**
     * Delete a specific container by ID.
     * 
     * DELETE /api/admin/containers/{id}
     */
    @DeleteMapping("/containers/{id}")
    public ResponseEntity<Map<String, Object>> deleteContainer(@PathVariable("id") String id) {
        boolean deleted = containerRepository.delete(id);
        if (deleted) {
            return ResponseEntity.ok(Map.of(
                "status", "deleted",
                "containerId", id
            ));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Clear all events from the event repository.
     * 
     * POST /api/admin/events/clear
     */
    @PostMapping("/events/clear")
    public ResponseEntity<Map<String, Object>> clearAllEvents() {
        int count = eventRepository.clearAll();
        return ResponseEntity.ok(Map.of(
            "status", "cleared",
            "eventsRemoved", count
        ));
    }

    /**
     * Get current registry stats.
     * 
     * GET /api/admin/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
            "containerCount", containerRepository.count(),
            "eventCount", eventRepository.count()
        ));
    }
    
    /**
     * Sync model status from ML service for all known containers.
     * 
     * POST /api/admin/models/sync
     * 
     * This queries the ML service for each container and updates
     * the local model registry with the current status.
     */
    @PostMapping("/models/sync")
    public ResponseEntity<Map<String, Object>> syncModelsFromMlService() {
        List<String> containerIds = containerRepository.findAll().stream()
            .map(Container::id)
            .collect(Collectors.toList());
        
        int synced = modelRegistryService.syncFromMlService(containerIds);
        
        return ResponseEntity.ok(Map.of(
            "status", "synced",
            "containersChecked", containerIds.size(),
            "modelsSynced", synced
        ));
    }
    
    /**
     * Clear the model registry cache.
     * 
     * POST /api/admin/models/clear
     */
    @PostMapping("/models/clear")
    public ResponseEntity<Map<String, Object>> clearModelCache() {
        modelRegistryService.clearCache();
        return ResponseEntity.ok(Map.of(
            "status", "cleared"
        ));
    }

    /**
     * Get or set triage LLM enable flag (demo safety).
     */
    @GetMapping("/triage/enabled")
    public ResponseEntity<Map<String, Object>> getTriageEnabled() {
        return ResponseEntity.ok(Map.of(
                "enableLlm", triageToggleService.isEnabled()
        ));
    }

    @PostMapping("/triage/enabled")
    public ResponseEntity<Map<String, Object>> setTriageEnabled(@RequestBody Map<String, Object> body) {
        Object enabledVal = body.get("enableLlm");
        boolean enabled = enabledVal instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(enabledVal));
        triageToggleService.setEnabled(enabled);
        return ResponseEntity.ok(Map.of(
                "enableLlm", enabled
        ));
    }
}

