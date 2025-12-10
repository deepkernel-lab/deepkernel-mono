package com.deepkernel.core.api;

import com.deepkernel.contracts.model.AnomalyScore;
import com.deepkernel.contracts.model.FeatureVector;
import com.deepkernel.contracts.model.ShortWindowPayload;
import com.deepkernel.core.ports.AnomalyDetectionPort;
import com.deepkernel.core.service.FeatureExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentWindowController {

    private static final Logger log = LoggerFactory.getLogger(AgentWindowController.class);
    private final AnomalyDetectionPort anomalyDetectionPort;
    private final FeatureExtractor featureExtractor;
    private final SimpMessagingTemplate messagingTemplate;

    public AgentWindowController(AnomalyDetectionPort anomalyDetectionPort,
                                 FeatureExtractor featureExtractor,
                                 SimpMessagingTemplate messagingTemplate) {
        this.anomalyDetectionPort = anomalyDetectionPort;
        this.featureExtractor = featureExtractor;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping("/windows")
    public ResponseEntity<?> ingestWindow(@RequestBody ShortWindowPayload payload) {
        int count = payload.records() != null ? payload.records().size() : 0;
        log.info("Received window for container {} (records: {})", payload.containerId(), count);

        FeatureVector fv = featureExtractor.extract(payload);
        AnomalyScore score = anomalyDetectionPort.scoreWindow(payload.containerId(), fv);

        messagingTemplate.convertAndSend("/topic/events", java.util.Map.of(
                "type", "WINDOW_SCORED",
                "container_id", payload.containerId(),
                "ml_score", score.score(),
                "is_anomalous", score.anomalous()
        ));

        return ResponseEntity.accepted().body(new com.deepkernel.core.api.dto.AgentWindowResponse(score.anomalous() ? "anomalous" : "accepted"));
    }
}

