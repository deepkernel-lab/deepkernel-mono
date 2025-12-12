package com.deepkernel.core.api;

import com.deepkernel.cicd.GitHubChangeContextAdapter;
import com.deepkernel.contracts.model.ChangeContext;
import com.deepkernel.contracts.ports.ChangeContextPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Demo-only endpoint to manually set change context for a container.
 * This lets you paste a diff summary during the demo without real CI/CD integration.
 */
@RestController
@RequestMapping("/api/ui/demo/change-context")
public class DemoChangeContextController {

    private final ChangeContextPort changeContextPort;

    public DemoChangeContextController(ChangeContextPort changeContextPort) {
        this.changeContextPort = changeContextPort;
    }

    @PostMapping
    public ResponseEntity<?> upsert(@RequestBody Map<String, Object> body) {
        if (!(changeContextPort instanceof GitHubChangeContextAdapter adapter)) {
            return ResponseEntity.status(501).body(Map.of("error", "ChangeContextPort does not support demo updates"));
        }

        String containerId = (String) body.getOrDefault("container_id", body.getOrDefault("containerId", ""));
        String commitId = (String) body.getOrDefault("commit_id", body.getOrDefault("commitId", "demo"));
        String repoUrl = (String) body.getOrDefault("repo_url", body.getOrDefault("repoUrl", "demo"));
        String diffSummary = (String) body.getOrDefault("diff_summary", body.getOrDefault("diffSummary", ""));
        Object filesObj = body.getOrDefault("changed_files", body.getOrDefault("changedFiles", List.of()));

        @SuppressWarnings("unchecked")
        List<String> changedFiles = filesObj instanceof List ? (List<String>) filesObj : List.of();

        if (containerId == null || containerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "container_id required"));
        }

        ChangeContext ctx = new ChangeContext(
                containerId,
                commitId,
                repoUrl,
                changedFiles,
                diffSummary,
                Instant.now()
        );
        adapter.registerDeployment(ctx);
        return ResponseEntity.ok(Map.of("status", "ok", "container_id", containerId));
    }

    @GetMapping("/{containerId}")
    public ResponseEntity<?> get(@PathVariable String containerId) {
        ChangeContext ctx = changeContextPort.getChangeContext(containerId, Instant.now().minusSeconds(3600));
        return ResponseEntity.ok(ctx);
    }
}

