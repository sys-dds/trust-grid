package com.trustgrid.api.policyengine;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyExceptionService {
    private final PolicyExceptionRepository repository;
    private final OutboxRepository outboxRepository;

    public PolicyExceptionService(PolicyExceptionRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        UUID id = repository.create(request);
        outboxRepository.insert("POLICY_EXCEPTION", id, null, "POLICY_EXCEPTION_REQUESTED",
                Map.of("policyName", request.get("policyName"), "targetType", request.get("targetType")));
        return repository.get(id);
    }

    @Transactional
    public Map<String, Object> approve(UUID id, Map<String, Object> request) {
        repository.approve(id, request);
        outboxRepository.insert("POLICY_EXCEPTION", id, null, "POLICY_EXCEPTION_APPROVED", Map.of("exceptionId", id));
        return repository.get(id);
    }

    @Transactional
    public Map<String, Object> reject(UUID id, Map<String, Object> request) {
        repository.reject(id, request);
        outboxRepository.insert("POLICY_EXCEPTION", id, null, "POLICY_EXCEPTION_REJECTED", Map.of("exceptionId", id));
        return repository.get(id);
    }

    @Transactional
    public Map<String, Object> revoke(UUID id, Map<String, Object> request) {
        repository.revoke(id, request);
        outboxRepository.insert("POLICY_EXCEPTION", id, null, "POLICY_EXCEPTION_REVOKED", Map.of("exceptionId", id));
        return repository.get(id);
    }

    public List<Map<String, Object>> list(String status) {
        return repository.list(status);
    }

    public Map<String, Object> get(UUID id) {
        return repository.get(id);
    }
}
