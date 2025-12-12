package com.deepkernel.core.api;

import com.deepkernel.core.repo.AnomalyWindowRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/ui/containers")
public class ContainerScoresController {

    public record ScorePoint(Instant ts, double score, boolean anomalous) {}

    private final AnomalyWindowRepository windowRepository;

    public ContainerScoresController(AnomalyWindowRepository windowRepository) {
        this.windowRepository = windowRepository;
    }

    @GetMapping("/{id}/scores")
    public ResponseEntity<List<ScorePoint>> scores(@PathVariable("id") String id,
                                                   @RequestParam(value = "limit", defaultValue = "50") int limit) {
        var windows = windowRepository.findByContainer(id, Math.max(1, Math.min(limit, 500)));
        var points = windows.stream()
                .map(w -> new ScorePoint(w.windowEnd(), w.mlScore(), w.anomalous()))
                .toList();
        return ResponseEntity.ok(points);
    }
}

