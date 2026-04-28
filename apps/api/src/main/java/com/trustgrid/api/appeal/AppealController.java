package com.trustgrid.api.appeal;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppealController {
    private final AppealService service;

    public AppealController(AppealService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/participants/{participantId}/appeals")
    public AppealResponse create(@PathVariable UUID participantId, @Valid @RequestBody CreateAppealRequest request) {
        return service.create(participantId, request);
    }

    @PostMapping("/api/v1/appeals/{appealId}/status")
    public AppealResponse status(@PathVariable UUID appealId, @RequestBody Map<String, Object> request) {
        return service.status(appealId, request);
    }

    @PostMapping("/api/v1/appeals/{appealId}/decide")
    public AppealResponse decide(@PathVariable UUID appealId, @Valid @RequestBody DecideAppealRequest request) {
        return service.decide(appealId, request);
    }

    @GetMapping("/api/v1/appeals")
    public List<AppealResponse> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @GetMapping("/api/v1/appeals/{appealId}")
    public AppealResponse get(@PathVariable UUID appealId) {
        return service.get(appealId);
    }
}
