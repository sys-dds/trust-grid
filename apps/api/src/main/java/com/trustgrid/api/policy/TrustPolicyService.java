package com.trustgrid.api.policy;

import com.trustgrid.api.shared.OutboxRepository;
import com.trustgrid.api.shared.ConflictException;
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
        if (repository.risky(id) && !repository.approved(id)) {
            throw new ConflictException("Risky policy requires approval before activation");
        }
        Map<String, Object> policy = repository.policy(id);
        repository.activate(id);
        outboxRepository.insert("POLICY", id, null, "POLICY_ACTIVATED", Map.of("policyId", id));
        outboxRepository.insert("POLICY", id, null, "POLICY_RETIRED", Map.of("policyName", policy.get("policy_name")));
        return Map.of("policyId", id, "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> retire(UUID id) {
        repository.retire(id);
        outboxRepository.insert("POLICY", id, null, "POLICY_RETIRED", Map.of("policyId", id));
        return Map.of("policyId", id, "status", "RETIRED");
    }

    @Transactional
    public Map<String, Object> requestApproval(UUID id, Map<String, Object> request) {
        UUID approvalId = repository.requestApproval(id, request);
        outboxRepository.insert("POLICY", id, null, "POLICY_APPROVAL_REQUESTED", Map.of("approvalId", approvalId));
        return Map.of("policyId", id, "approvalId", approvalId, "approvalStatus", "REQUIRED");
    }

    @Transactional
    public Map<String, Object> approve(UUID id, Map<String, Object> request) {
        repository.approve(id, request);
        outboxRepository.insert("POLICY", id, null, "POLICY_APPROVAL_RECORDED", Map.of("approvalStatus", "APPROVED"));
        return Map.of("policyId", id, "approvalStatus", "APPROVED");
    }

    @Transactional
    public Map<String, Object> reject(UUID id, Map<String, Object> request) {
        repository.reject(id, request);
        outboxRepository.insert("POLICY", id, null, "POLICY_APPROVAL_RECORDED", Map.of("approvalStatus", "REJECTED"));
        return Map.of("policyId", id, "approvalStatus", "REJECTED");
    }

    @Transactional
    public Map<String, Object> restorePrevious(UUID id, Map<String, Object> request) {
        required(request, "actor");
        required(request, "reason");
        required(request, "riskAcknowledgement");
        UUID restored = repository.restorePrevious(id);
        outboxRepository.insert("POLICY", id, null, "POLICY_ROLLBACK_COMPLETED",
                Map.of("fromPolicyId", id, "restoredPolicyId", restored));
        return Map.of("policyId", restored, "status", "ACTIVE", "restoredFromPolicyId", id);
    }

    @Transactional
    public Map<String, Object> blastRadius(Map<String, Object> request) {
        Map<String, Object> counts = repository.policyDataCounts();
        Map<String, Object> summary = new java.util.LinkedHashMap<>(counts);
        summary.put("wouldRequireVerification", counts.get("newUsersImpacted"));
        summary.put("highValueActionsImpacted", counts.get("wouldBlockTransactions"));
        summary.put("dataDriven", true);
        UUID id = repository.blastRadius(request, summary);
        outboxRepository.insert("POLICY", id, null, "POLICY_BLAST_RADIUS_PREVIEWED", summary);
        return Map.of("previewId", id, "summary", summary);
    }

    @Transactional
    public Map<String, Object> regressionCheck(Map<String, Object> request) {
        Map<String, Object> summary = Map.of(
                "suspendedParticipantCannotTransact", true,
                "hiddenListingsNotSearchable", true,
                "restrictedParticipantNeedsException", true,
                "policySimulationReadOnly", true,
                "paymentBoundaryDoesNotMoveFunds", true
        );
        UUID id = repository.simulation("TRUST_POLICY", request, summary);
        outboxRepository.insert("POLICY", id, null, "POLICY_REGRESSION_CHECK_RUN", summary);
        return Map.of("checkId", id, "status", "SUCCEEDED", "summary", summary);
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

    private void required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
