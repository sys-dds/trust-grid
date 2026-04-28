package com.trustgrid.api.ops;

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
public class OpsQueueController {
    private final OpsQueueService service;

    public OpsQueueController(OpsQueueService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/ops/queue")
    public List<OpsQueueItemResponse> search(@RequestParam(required = false) String queueType,
                                             @RequestParam(required = false) String status) {
        return service.search(queueType, status);
    }

    @GetMapping("/api/v1/ops/queue/{queueItemId}")
    public OpsQueueItemResponse get(@PathVariable UUID queueItemId) {
        return service.get(queueItemId);
    }

    @PostMapping("/api/v1/ops/queue")
    public OpsQueueItemResponse create(@Valid @RequestBody CreateOpsQueueItemRequest request) {
        return service.create(request);
    }

    @PostMapping("/api/v1/ops/queue/rebuild")
    public Map<String, Object> rebuild() {
        return service.rebuild();
    }

    @PostMapping("/api/v1/ops/queue/{queueItemId}/status")
    public OpsQueueItemResponse status(@PathVariable UUID queueItemId, @RequestBody Map<String, Object> request) {
        return service.update(queueItemId, request);
    }
}
