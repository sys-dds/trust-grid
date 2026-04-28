package com.trustgrid.api.reputation;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReputationController {

    private final ReputationService service;

    public ReputationController(ReputationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/participants/{participantId}/reputation")
    public ReputationSnapshotResponse get(@PathVariable UUID participantId) {
        return service.get(participantId);
    }
}
