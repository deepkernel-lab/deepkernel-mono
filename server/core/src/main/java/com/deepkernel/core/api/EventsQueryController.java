package com.deepkernel.core.api;

import com.deepkernel.core.service.model.LiveEvent;
import com.deepkernel.core.repo.EventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@RestController
@RequestMapping("/api/ui/events")
public class EventsQueryController {
    private final EventRepository eventRepository;

    public EventsQueryController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping
    public ResponseEntity<List<LiveEvent>> latest(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(eventRepository.latest(limit));
    }

    @GetMapping("/container/{id}")
    public ResponseEntity<List<LiveEvent>> byContainer(@PathVariable("id") String id,
                                                       @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(eventRepository.byContainer(id, limit));
    }
}

