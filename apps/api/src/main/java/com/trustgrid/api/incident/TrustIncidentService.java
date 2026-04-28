package com.trustgrid.api.incident;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrustIncidentService {
    private final TrustIncidentRepository repository;
    private final OutboxRepository outboxRepository;

    public TrustIncidentService(TrustIncidentRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        UUID id = repository.create(request);
        outboxRepository.insert("TRUST_INCIDENT", id, null, "TRUST_INCIDENT_CREATED", Map.of("incidentId", id));
        return repository.get(id);
    }

    @Transactional
    public Map<String, Object> status(UUID id, Map<String, Object> request) {
        Map<String, Object> incident = repository.updateStatus(id, request);
        String eventType = "RESOLVED".equals(incident.get("status")) ? "TRUST_INCIDENT_RESOLVED" : "TRUST_INCIDENT_UPDATED";
        outboxRepository.insert("TRUST_INCIDENT", id, null, eventType, Map.of("status", incident.get("status")));
        return incident;
    }

    public List<Map<String, Object>> list() {
        return repository.list();
    }

    public Map<String, Object> get(UUID id) {
        return repository.get(id);
    }

    public List<Map<String, Object>> timeline(UUID id) {
        return repository.timeline(id);
    }

    public Map<String, Object> impact(UUID id) {
        return repository.latestImpact(id);
    }

    public Map<String, Object> evidenceBundle(UUID id) {
        return repository.evidenceBundle(id);
    }

    public Map<String, Object> metrics() {
        return repository.metrics();
    }

    @Transactional
    public Map<String, Object> replay(UUID id) {
        Map<String, Object> replay = repository.replay(id);
        outboxRepository.insert("TRUST_INCIDENT", id, null, "TRUST_INCIDENT_REPLAYED", replay);
        return replay;
    }
}
