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
public class SafetyEscalationController {
    private final OpsQueueRepository repository;
    private final OutboxRepository outboxRepository;

    public SafetyEscalationController(OpsQueueRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @PostMapping("/api/v1/ops/safety-escalations")
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        UUID id = repository.safetyEscalation(required(request, "targetType"), UUID.fromString(required(request, "targetId")),
                request.getOrDefault("severity", "HIGH").toString(), required(request, "actor"), required(request, "reason"));
        outboxRepository.insert("SAFETY_ESCALATION", id, null, "SAFETY_ESCALATION_CREATED", Map.of("severity", request.getOrDefault("severity", "HIGH")));
        return Map.of("escalationId", id, "status", "OPEN");
    }

    @PostMapping("/api/v1/ops/safety-escalations/{escalationId}/status")
    public Map<String, Object> status(@PathVariable UUID escalationId, @RequestBody Map<String, Object> request) {
        repository.status("safety_escalations", escalationId, required(request, "status"));
        return Map.of("escalationId", escalationId, "status", request.get("status"));
    }

    @GetMapping("/api/v1/ops/safety-escalations")
    public List<Map<String, Object>> list() {
        return repository.rows("safety_escalations");
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
