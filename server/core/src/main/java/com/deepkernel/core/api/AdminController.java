package com.deepkernel.core.api;

import com.deepkernel.core.repo.ContainerRepository;
import com.deepkernel.core.repo.EventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    public AdminController(ContainerRepository containerRepository, EventRepository eventRepository) {
        this.containerRepository = containerRepository;
        this.eventRepository = eventRepository;
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
}

