package com.deepkernel.core.repo;

import com.deepkernel.contracts.model.Container;
import com.deepkernel.contracts.model.enums.ModelStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

@Repository
public class ContainerRepository {
    private final ConcurrentMap<String, Container> containers = new ConcurrentHashMap<>();

    public ContainerRepository() {
        // Seed with an example to power UI quickly.
        Container example = new Container("billing-api", "prod", "worker-01", "Running", true, ModelStatus.UNTRAINED);
        containers.put(example.id(), example);
    }

    public List<Container> findAll() {
        return Collections.unmodifiableList(new ArrayList<>(containers.values()));
    }

    public void upsert(Container container) {
        containers.put(container.id(), container);
    }

    /**
     * Delete a specific container by ID.
     * @return true if container was found and removed
     */
    public boolean delete(String containerId) {
        return containers.remove(containerId) != null;
    }

    /**
     * Delete all containers matching a regex pattern.
     * @param pattern Regex pattern to match container IDs
     * @return number of containers deleted
     */
    public int deleteByPattern(String pattern) {
        Pattern p = Pattern.compile(pattern);
        List<String> toDelete = containers.keySet().stream()
            .filter(id -> p.matcher(id).matches())
            .toList();
        toDelete.forEach(containers::remove);
        return toDelete.size();
    }

    /**
     * Clear all containers from the registry.
     * @return number of containers cleared
     */
    public int clearAll() {
        int count = containers.size();
        containers.clear();
        return count;
    }

    /**
     * Get count of registered containers.
     */
    public int count() {
        return containers.size();
    }
}

