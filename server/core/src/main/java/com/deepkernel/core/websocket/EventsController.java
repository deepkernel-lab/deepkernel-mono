package com.deepkernel.core.websocket;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class EventsController {

    @MessageMapping("/events/echo")
    @SendTo("/topic/events")
    public Map<String, Object> echo(Map<String, Object> message) {
        // Placeholder echo handler; replace with real event streaming.
        return message;
    }
}

