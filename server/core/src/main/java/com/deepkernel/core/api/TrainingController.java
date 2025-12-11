package com.deepkernel.core.api;

import com.deepkernel.contracts.model.TrainingContext;
import com.deepkernel.core.service.TrainingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@RequestMapping("/api/ui/train")
public class TrainingController {
    private final TrainingService trainingService;

    public TrainingController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @PostMapping("/{containerId}")
    public ResponseEntity<Void> triggerTraining(@PathVariable("containerId") String containerId,
                                                @RequestBody(required = false) TrainingContext ctx) {
        trainingService.train(containerId, Collections.emptyList(), ctx);
        return ResponseEntity.accepted().build();
    }
}

