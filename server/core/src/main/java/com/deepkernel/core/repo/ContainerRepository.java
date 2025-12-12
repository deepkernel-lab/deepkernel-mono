package com.deepkernel.core.repo;

import com.deepkernel.contracts.model.Container;
import com.deepkernel.contracts.model.enums.ModelStatus;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
}

