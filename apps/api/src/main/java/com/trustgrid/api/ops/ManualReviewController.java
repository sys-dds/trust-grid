package com.trustgrid.api.ops;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManualReviewController {
    private final OpsQueueRepository repository;
    private final OutboxRepository outboxRepository;

    public ManualReviewController(OpsQueueRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @PostMapping("/api/v1/ops/manual-review-cases")
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        UUID id = repository.manualCase(required(request, "targetType"), UUID.fromString(required(request, "targetId")),
                required(request, "actor"), required(request, "reason"),
                request.get("queueItemId") == null ? null : UUID.fromString(request.get("queueItemId").toString()));
        outboxRepository.insert("MANUAL_REVIEW", id, null, "MANUAL_REVIEW_CASE_CREATED", Map.of("targetType", request.get("targetType")));
        return Map.of("caseId", id, "status", "OPEN");
    }

    @PostMapping("/api/v1/ops/manual-review-cases/{caseId}/status")
    public Map<String, Object> status(@PathVariable UUID caseId, @RequestBody Map<String, Object> request) {
        repository.status("manual_review_cases", caseId, required(request, "status"));
        outboxRepository.insert("MANUAL_REVIEW", caseId, null, "MANUAL_REVIEW_STATUS_UPDATED", Map.of("status", request.get("status")));
        return Map.of("caseId", caseId, "status", request.get("status"));
    }

    @GetMapping("/api/v1/ops/manual-review-cases")
    public List<Map<String, Object>> list() {
        return repository.rows("manual_review_cases");
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
