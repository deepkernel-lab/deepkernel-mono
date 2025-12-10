package com.deepkernel.core.repo;

import com.deepkernel.contracts.model.AnomalyWindow;
import com.deepkernel.contracts.model.enums.TriageStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Repository
public class AnomalyWindowRepository {
    private final List<AnomalyWindow> windows = new CopyOnWriteArrayList<>();

    public void save(AnomalyWindow window) {
        windows.removeIf(w -> w.id().equals(window.id()));
        windows.add(window);
    }

    public List<AnomalyWindow> findByContainer(String containerId, int limit) {
        List<AnomalyWindow> filtered = windows.stream()
                .filter(w -> w.containerId().equals(containerId))
                .collect(Collectors.toList());
        int from = Math.max(filtered.size() - limit, 0);
        return Collections.unmodifiableList(filtered.subList(from, filtered.size()));
    }

    public AnomalyWindow latest(String containerId) {
        List<AnomalyWindow> filtered = windows.stream()
                .filter(w -> w.containerId().equals(containerId))
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return null;
        }
        return filtered.get(filtered.size() - 1);
    }
}

