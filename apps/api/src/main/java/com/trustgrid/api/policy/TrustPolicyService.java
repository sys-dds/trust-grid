package com.trustgrid.api.policy;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrustPolicyService {
    private final TrustPolicyRepository repository;
    private final OutboxRepository outboxRepository;

    public TrustPolicyService(TrustPolicyRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        UUID id = repository.create(request);
        outboxRepository.insert("POLICY", id, null, "TRUST_POLICY_VERSION_CREATED", Map.of("policyName", request.get("policyName")));
        return Map.of("policyId", id, "status", "DRAFT");
    }

    @Transactional
    public Map<String, Object> activate(UUID id) {
        repository.activate(id);
        return Map.of("policyId", id, "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> retire(UUID id) {
        repository.retire(id);
        return Map.of("policyId", id, "status", "RETIRED");
    }

    public List<Map<String, Object>> policies() {
        return repository.policies();
    }

    public List<Map<String, Object>> active() {
        return repository.active();
    }

    public List<Map<String, Object>> campaigns() {
        return repository.abuseCampaigns();
    }

    public Map<String, Object> retention() {
        return repository.retentionSummary();
    }
}
