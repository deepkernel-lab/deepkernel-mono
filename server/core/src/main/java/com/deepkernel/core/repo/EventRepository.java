package com.deepkernel.core.repo;

import com.deepkernel.core.service.model.LiveEvent;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Repository
public class EventRepository {
    private final List<LiveEvent> events = new CopyOnWriteArrayList<>();

    public void save(LiveEvent event) {
        events.add(event);
    }

    public List<LiveEvent> latest(int limit) {
        int from = Math.max(events.size() - limit, 0);
        return events.subList(from, events.size());
    }

    public List<LiveEvent> byContainer(String containerId, int limit) {
        List<LiveEvent> filtered = events.stream()
                .filter(e -> containerId.equals(e.containerId()))
                .collect(Collectors.toList());
        int from = Math.max(filtered.size() - limit, 0);
        return filtered.subList(from, filtered.size());
    }

    /**
     * Clear all events.
     * @return number of events cleared
     */
    public int clearAll() {
        int count = events.size();
        events.clear();
        return count;
    }

    /**
     * Get count of stored events.
     */
    public int count() {
        return events.size();
    }
}

