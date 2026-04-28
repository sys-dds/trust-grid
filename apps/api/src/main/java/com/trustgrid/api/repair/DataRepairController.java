package com.trustgrid.api.repair;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DataRepairController {
    private final DataRepairService service;

    public DataRepairController(DataRepairService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/data-repair/recommendations/generate")
    public Map<String, Object> generate() {
        return service.generate();
    }

    @GetMapping("/api/v1/data-repair/recommendations")
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/api/v1/data-repair/recommendations/{recommendationId}")
    public Map<String, Object> get(@PathVariable UUID recommendationId) {
        return service.get(recommendationId);
    }

    @PostMapping("/api/v1/data-repair/recommendations/{recommendationId}/approve")
    public Map<String, Object> approve(@PathVariable UUID recommendationId, @RequestBody Map<String, Object> request) {
        return service.approve(recommendationId, request);
    }

    @PostMapping("/api/v1/data-repair/recommendations/{recommendationId}/apply")
    public Map<String, Object> apply(@PathVariable UUID recommendationId, @RequestBody Map<String, Object> request) {
        return service.apply(recommendationId, request);
    }

    @PostMapping("/api/v1/data-repair/recommendations/{recommendationId}/reject")
    public Map<String, Object> reject(@PathVariable UUID recommendationId, @RequestBody Map<String, Object> request) {
        return service.reject(recommendationId, request);
    }

    @GetMapping("/api/v1/data-repair/actions")
    public List<Map<String, Object>> actions() {
        return service.actions();
    }
}
