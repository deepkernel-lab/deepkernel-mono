package com.deepkernel.core.repo;

import com.deepkernel.contracts.model.Policy;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Repository
public class PolicyRepository {
    private final List<Policy> policies = new CopyOnWriteArrayList<>();

    public void save(Policy policy) {
        policies.add(policy);
    }

    public List<Policy> findByContainer(String containerId) {
        return policies.stream()
                .filter(p -> p.containerId().equals(containerId))
                .collect(Collectors.toList());
    }

    public Policy latest(String containerId) {
        List<Policy> list = findByContainer(containerId);
        if (list.isEmpty()) return null;
        return list.get(list.size() - 1);
    }
}

