package com.deepkernel.core.repo;

import com.deepkernel.contracts.model.TriageResult;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class TriageResultRepository {
    private final List<TriageResult> results = new CopyOnWriteArrayList<>();

    public void save(TriageResult result) {
        results.add(result);
    }

    public TriageResult latestForWindow(String windowId) {
        return results.stream()
                .filter(r -> r.windowId().equals(windowId))
                .reduce((first, second) -> second)
                .orElse(null);
    }
}

