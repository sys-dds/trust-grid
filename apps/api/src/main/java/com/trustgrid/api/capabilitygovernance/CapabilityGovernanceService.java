package com.trustgrid.api.capabilitygovernance;

import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapabilityGovernanceService {
    private static final List<String> TRUST_ORDER = List.of("NEW", "LIMITED", "STANDARD", "TRUSTED", "HIGH_TRUST");
    private static final List<String> VERIFICATION_ORDER = List.of("UNVERIFIED", "BASIC", "VERIFIED", "ENHANCED");
    private static final List<String> RISK_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final CapabilityGovernanceRepository repository;
    private final OutboxRepository outboxRepository;

    public CapabilityGovernanceService(CapabilityGovernanceRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> createPolicy(Map<String, Object> request) {
        String actionName = repository.required(request, "actionName");
        if (!List.of("PUBLISH_LISTING", "ACCEPT_TRANSACTION", "OPEN_DISPUTE", "CREATE_REVIEW",
                "RECEIVE_SEARCH_EXPOSURE", "REQUEST_PAYMENT_RELEASE").contains(actionName)) {
            throw new IllegalArgumentException("Unsupported capability governance action");
        }
        UUID id = repository.createPolicy(request);
        UUID participantId = repository.optionalUuid(request.get("participantId"));
        if (participantId != null) {
            repository.timeline(participantId, actionName, null, null,
                    "CAPABILITY_POLICY_CREATED", repository.required(request, "createdBy"),
                    repository.required(request, "reason"), Map.of("policyId", id));
        }
        outboxRepository.insert("CAPABILITY_POLICY", id, participantId, "CAPABILITY_POLICY_CREATED",
                Map.of("actionName", request.get("actionName"), "policyName", request.get("policyName"),
                        "policyVersion", request.get("policyVersion")));
        return Map.of("policyId", id, "status", "CREATED");
    }

    public List<Map<String, Object>> policies() {
        return repository.policies();
    }

    public Map<String, Object> policy(UUID id) {
        return repository.policy(id);
    }

    @Transactional
    public Map<String, Object> simulate(Map<String, Object> request) {
        return evaluateCapability(request, true);
    }

    @Transactional
    public Map<String, Object> replay(UUID decisionId) {
        Map<String, Object> original = repository.decision(decisionId);
        Map<String, Object> snapshot = repository.readMap(original.get("input_snapshot_json").toString());
        Map<String, Object> replayRequest = new LinkedHashMap<>();
        replayRequest.put("participantId", original.get("participant_id").toString());
        replayRequest.put("actionName", original.get("action_name").toString());
        replayRequest.put("targetType", string(original.get("target_type")));
        if (original.get("target_id") != null) {
            replayRequest.put("targetId", original.get("target_id").toString());
        }
        replayRequest.put("policyName", original.get("policy_name").toString());
        replayRequest.put("policyVersion", original.get("policy_version").toString());
        Map<String, Object> replayed = evaluateSnapshot(replayRequest, snapshot);
        List<Map<String, Object>> originalReasons = repository.readList(original.get("deny_reasons_json").toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replayedReasons = (List<Map<String, Object>>) replayed.get("denyReasons");
        List<String> mismatches = new ArrayList<>();
        if (!Objects.equals(original.get("decision"), replayed.get("decision"))) {
            mismatches.add("decision changed");
        }
        if (!Objects.equals(reasonCodes(originalReasons), reasonCodes(replayedReasons))) {
            mismatches.add("deny reasons changed");
        }
        if (!Objects.equals(original.get("policy_name"), replayed.get("policyName"))
                || !Objects.equals(original.get("policy_version"), replayed.get("policyVersion"))) {
            mismatches.add("policy changed");
        }
        UUID participantId = (UUID) original.get("participant_id");
        repository.timeline(participantId, original.get("action_name").toString(), string(original.get("target_type")),
                (UUID) original.get("target_id"), "CAPABILITY_DECISION_REPLAYED", "operator@example.com",
                "Capability decision replay", Map.of("decisionId", decisionId, "matchedOriginal", mismatches.isEmpty()));
        outboxRepository.insert("CAPABILITY_GOVERNANCE", decisionId, participantId, "CAPABILITY_DECISION_REPLAYED",
                Map.of("matchedOriginal", mismatches.isEmpty()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("decisionId", decisionId);
        response.put("originalDecision", original.get("decision"));
        response.put("replayedDecision", replayed.get("decision"));
        response.put("matchedOriginal", mismatches.isEmpty());
        response.put("mismatchReasons", mismatches);
        response.put("deterministic", true);
        response.put("policyName", original.get("policy_name"));
        response.put("policyVersion", original.get("policy_version"));
        response.put("actionName", original.get("action_name"));
        response.put("targetType", original.get("target_type"));
        response.put("targetId", original.get("target_id"));
        response.put("originalSnapshot", snapshot);
        response.put("replayedReasons", replayedReasons);
        return response;
    }

    @Transactional
    public Map<String, Object> blastRadiusPreview(Map<String, Object> request) {
        String actionName = repository.required(request, "actionName");
        List<UUID> candidates = repository.candidateParticipants((List<?>) request.get("candidateParticipantIds"));
        int allowed = 0;
        int denied = 0;
        int manual = 0;
        int verification = 0;
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
        List<Map<String, Object>> affected = new ArrayList<>();
        for (UUID participantId : candidates) {
            Map<String, Object> evaluationRequest = new LinkedHashMap<>(request);
            evaluationRequest.put("participantId", participantId.toString());
            evaluationRequest.put("actor", request.getOrDefault("requestedBy", "operator@example.com"));
            evaluationRequest.put("reason", request.getOrDefault("reason", "Capability blast-radius preview"));
            Map<String, Object> result = evaluateCapability(evaluationRequest, false);
            String decision = result.get("decision").toString();
            if (decision.startsWith("ALLOW")) {
                allowed++;
            } else {
                denied++;
                affected.add(Map.of("participantId", participantId, "decision", decision,
                        "denyReasons", result.get("denyReasons")));
            }
            if ("REQUIRE_MANUAL_REVIEW".equals(decision)) {
                manual++;
            }
            if ("REQUIRE_VERIFICATION".equals(decision)) {
                verification++;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reasons = (List<Map<String, Object>>) result.get("denyReasons");
            for (Map<String, Object> reason : reasons) {
                String code = reason.get("code").toString();
                reasonCounts.put(code, reasonCounts.getOrDefault(code, 0) + 1);
            }
        }
        outboxRepository.insert("CAPABILITY_GOVERNANCE", UUID.randomUUID(), null, "CAPABILITY_BLAST_RADIUS_PREVIEWED",
                Map.of("actionName", actionName, "candidateCount", candidates.size(), "deniedCount", denied));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("actionName", actionName);
        response.put("candidateCount", candidates.size());
        response.put("allowedCount", allowed);
        response.put("deniedCount", denied);
        response.put("manualReviewCount", manual);
        response.put("requireVerificationCount", verification);
        response.put("affectedParticipants", affected.stream().limit(25).toList());
        response.put("topDenyReasons", reasonCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(entry -> Map.of("code", entry.getKey(), "count", entry.getValue()))
                .toList());
        response.put("deterministic", true);
        return response;
    }

    public Map<String, Object> auditBundle(UUID participantId) {
        return repository.auditBundle(participantId);
    }

    public Map<String, Object> dashboard() {
        return repository.dashboard();
    }

    private Map<String, Object> evaluateCapability(Map<String, Object> request, boolean recordDecision) {
        UUID participantId = repository.uuid(request, "participantId");
        String actionName = repository.required(request, "actionName");
        String targetType = string(request.get("targetType"));
        UUID targetId = repository.optionalUuid(request.get("targetId"));
        Map<String, Object> participant = repository.participant(participantId);
        Map<String, Object> policy = repository.policyFor(actionName, string(request.get("policyName")),
                        string(request.get("policyVersion")))
                .orElseGet(() -> defaultPolicy(actionName));
        Map<String, Object> target = repository.targetSnapshot(targetType, targetId);
        List<Map<String, Object>> denyReasons = new ArrayList<>();
        List<String> capabilities = repository.activeCapabilities(participantId);
        List<Map<String, Object>> restrictions = repository.activeRestrictions(participantId);
        Long valueCents = valueCents(request, target);
        evaluateAccount(participant, denyReasons);
        evaluateTrustTier(policy, participant, denyReasons);
        evaluateVerification(policy, participant, denyReasons);
        evaluateRisk(policy, participant, denyReasons);
        evaluateCapabilities(policy, actionName, capabilities, denyReasons);
        evaluateRestrictions(policy, actionName, restrictions, valueCents, denyReasons);
        evaluateValue(policy, valueCents, denyReasons);
        evaluateTarget(actionName, target, denyReasons);

        var temporaryGrant = repository.matchingTemporaryGrant(participantId, actionName, targetType, targetId);
        var breakGlass = repository.matchingBreakGlass(participantId, actionName, targetType, targetId);
        String decision = decision(actionName, denyReasons, temporaryGrant.isPresent(), breakGlass.isPresent());
        List<Map<String, Object>> nextSteps = nextSteps(denyReasons);
        Map<String, Object> inputSnapshot = new LinkedHashMap<>();
        inputSnapshot.put("participant", publicParticipantSnapshot(participant));
        inputSnapshot.put("activeCapabilities", capabilities);
        inputSnapshot.put("activeRestrictions", restrictions.stream().map(row -> row.get("restriction_type")).toList());
        inputSnapshot.put("target", target);
        inputSnapshot.put("valueCents", valueCents);
        temporaryGrant.ifPresent(grant -> inputSnapshot.put("appliedGrantId", grant.get("id")));
        breakGlass.ifPresent(override -> inputSnapshot.put("appliedBreakGlassId", override.get("id")));
        inputSnapshot.put("policySnapshot", policy);
        UUID decisionId = null;
        if (recordDecision) {
            decisionId = repository.insertDecision(participantId, actionName, targetType, targetId, decision,
                    policy.get("policy_name").toString(), policy.get("policy_version").toString(),
                    denyReasons, nextSteps, inputSnapshot, policy);
            repository.timeline(participantId, actionName, targetType, targetId, "CAPABILITY_SIMULATED",
                    request.getOrDefault("actor", "operator@example.com").toString(),
                    request.getOrDefault("reason", "Capability simulation").toString(),
                    Map.of("decisionId", decisionId, "decision", decision, "denyReasonCount", denyReasons.size()));
            repository.timeline(participantId, actionName, targetType, targetId, "CAPABILITY_DECISION_LOGGED",
                    request.getOrDefault("actor", "operator@example.com").toString(),
                    request.getOrDefault("reason", "Capability decision logged").toString(),
                    Map.of("decisionId", decisionId));
            outboxRepository.insert("CAPABILITY_GOVERNANCE", decisionId, participantId, "CAPABILITY_SIMULATED",
                    Map.of("actionName", actionName, "decision", decision));
            outboxRepository.insert("CAPABILITY_GOVERNANCE", decisionId, participantId, "CAPABILITY_DECISION_LOGGED",
                    Map.of("actionName", actionName, "decision", decision));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("decisionId", decisionId);
        response.put("participantId", participantId);
        response.put("actionName", actionName);
        response.put("targetType", targetType);
        response.put("targetId", targetId);
        response.put("decision", decision);
        response.put("policyName", policy.get("policy_name"));
        response.put("policyVersion", policy.get("policy_version"));
        response.put("denyReasons", denyReasons);
        response.put("nextSteps", nextSteps);
        response.put("inputSnapshot", inputSnapshot);
        response.put("deterministicRulesVersion", "capability_governance_v1");
        temporaryGrant.ifPresent(grant -> response.put("appliedGrantId", grant.get("id")));
        breakGlass.ifPresent(override -> response.put("appliedBreakGlassId", override.get("id")));
        return response;
    }

    private Map<String, Object> evaluateSnapshot(Map<String, Object> request, Map<String, Object> snapshot) {
        String actionName = repository.required(request, "actionName");
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotPolicy = (Map<String, Object>) snapshot.get("policySnapshot");
        Map<String, Object> policy = snapshotPolicy != null ? snapshotPolicy
                : repository.policyFor(actionName, string(request.get("policyName")),
                        string(request.get("policyVersion"))).orElseGet(() -> defaultPolicy(actionName));
        @SuppressWarnings("unchecked")
        Map<String, Object> participantSnapshot = (Map<String, Object>) snapshot.getOrDefault("participant", Map.of());
        Map<String, Object> participant = new LinkedHashMap<>();
        participant.put("account_status", participantSnapshot.getOrDefault("accountStatus", "ACTIVE"));
        participant.put("verification_status", participantSnapshot.getOrDefault("verificationStatus", "UNVERIFIED"));
        participant.put("trust_tier", participantSnapshot.getOrDefault("trustTier", "NEW"));
        participant.put("risk_level", participantSnapshot.getOrDefault("riskLevel", "LOW"));
        participant.put("trust_score", participantSnapshot.getOrDefault("trustScore", 500));
        participant.put("trust_confidence", participantSnapshot.getOrDefault("trustConfidence", 0));
        @SuppressWarnings("unchecked")
        List<String> capabilities = (List<String>) snapshot.getOrDefault("activeCapabilities", List.of());
        @SuppressWarnings("unchecked")
        List<Object> restrictionValues = (List<Object>) snapshot.getOrDefault("activeRestrictions", List.of());
        List<Map<String, Object>> restrictions = restrictionValues.stream()
                .map(value -> Map.<String, Object>of("restriction_type", value.toString()))
                .toList();
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) snapshot.getOrDefault("target", Map.of());
        Long valueCents = snapshot.get("valueCents") instanceof Number number ? number.longValue() : null;
        List<Map<String, Object>> denyReasons = new ArrayList<>();
        evaluateAccount(participant, denyReasons);
        evaluateTrustTier(policy, participant, denyReasons);
        evaluateVerification(policy, participant, denyReasons);
        evaluateRisk(policy, participant, denyReasons);
        evaluateCapabilities(policy, actionName, capabilities, denyReasons);
        evaluateRestrictions(policy, actionName, restrictions, valueCents, denyReasons);
        evaluateValue(policy, valueCents, denyReasons);
        evaluateTarget(actionName, target, denyReasons);
        String decision = decision(actionName, denyReasons, snapshot.containsKey("appliedGrantId"),
                snapshot.containsKey("appliedBreakGlassId"));
        return Map.of(
                "decision", decision,
                "denyReasons", denyReasons,
                "policyName", policy.get("policy_name"),
                "policyVersion", policy.get("policy_version")
        );
    }

    @Transactional
    public Map<String, Object> createTemporaryGrant(Map<String, Object> request) {
        UUID id = repository.createTemporaryGrant(request);
        UUID participantId = repository.uuid(request, "participantId");
        repository.timeline(participantId, repository.required(request, "actionName"), string(request.get("targetType")),
                repository.optionalUuid(request.get("targetId")), "TEMPORARY_GRANT_CREATED",
                repository.required(request, "grantedBy"), repository.required(request, "reason"), Map.of("grantId", id));
        outboxRepository.insert("TEMPORARY_CAPABILITY_GRANT", id, participantId, "TEMPORARY_CAPABILITY_GRANT_CREATED",
                Map.of("actionName", request.get("actionName")));
        return Map.of("grantId", id, "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> revokeTemporaryGrant(UUID id, Map<String, Object> request) {
        Map<String, Object> grant = repository.temporaryGrant(id);
        int updated = repository.revokeTemporaryGrant(id, request);
        if (updated == 0) {
            throw new ConflictException("Temporary capability grant cannot be revoked from current state");
        }
        repository.timeline((UUID) grant.get("participant_id"), grant.get("action_name").toString(),
                string(grant.get("target_type")), (UUID) grant.get("target_id"), "TEMPORARY_GRANT_REVOKED",
                repository.required(request, "actor"), repository.required(request, "reason"), Map.of("grantId", id));
        outboxRepository.insert("TEMPORARY_CAPABILITY_GRANT", id, null, "TEMPORARY_CAPABILITY_GRANT_REVOKED", Map.of("grantId", id));
        return Map.of("grantId", id, "status", "REVOKED");
    }

    @Transactional
    public Map<String, Object> expireTemporaryGrants() {
        List<Map<String, Object>> expired = repository.expireTemporaryGrants();
        for (Map<String, Object> grant : expired) {
            UUID participantId = (UUID) grant.get("participant_id");
            UUID grantId = (UUID) grant.get("id");
            repository.timeline(participantId, grant.get("action_name").toString(), string(grant.get("target_type")),
                    (UUID) grant.get("target_id"), "TEMPORARY_GRANT_EXPIRED", "system",
                    "API-triggered temporary grant expiry", Map.of("grantId", grantId));
            outboxRepository.insert("TEMPORARY_CAPABILITY_GRANT", grantId, participantId,
                    "TEMPORARY_CAPABILITY_GRANT_EXPIRED", Map.of("grantId", grantId));
        }
        return Map.of("expired", expired.size(), "status", "SUCCEEDED");
    }

    public List<Map<String, Object>> temporaryGrants() {
        return repository.temporaryGrants();
    }

    @Transactional
    public Map<String, Object> createBreakGlass(Map<String, Object> request) {
        UUID id = repository.createBreakGlass(request);
        UUID participantId = repository.uuid(request, "participantId");
        repository.timeline(participantId, repository.required(request, "actionName"), string(request.get("targetType")),
                repository.optionalUuid(request.get("targetId")), "BREAK_GLASS_CREATED",
                repository.required(request, "actor"), repository.required(request, "reason"), Map.of("breakGlassId", id));
        outboxRepository.insert("BREAK_GLASS_CAPABILITY", id, participantId, "BREAK_GLASS_CAPABILITY_CREATED",
                Map.of("actionName", request.get("actionName")));
        return Map.of("breakGlassId", id, "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> revokeBreakGlass(UUID id, Map<String, Object> request) {
        Map<String, Object> override = repository.breakGlass(id);
        int updated = repository.revokeBreakGlass(id, request);
        if (updated == 0) {
            throw new ConflictException("Break-glass capability action cannot be revoked from current state");
        }
        repository.timeline((UUID) override.get("participant_id"), override.get("action_name").toString(),
                string(override.get("target_type")), (UUID) override.get("target_id"), "BREAK_GLASS_REVOKED",
                repository.required(request, "actor"), repository.required(request, "reason"), Map.of("breakGlassId", id));
        outboxRepository.insert("BREAK_GLASS_CAPABILITY", id, null, "BREAK_GLASS_CAPABILITY_REVOKED", Map.of("breakGlassId", id));
        return Map.of("breakGlassId", id, "status", "REVOKED");
    }

    @Transactional
    public Map<String, Object> expireBreakGlass() {
        List<Map<String, Object>> expired = repository.expireBreakGlass();
        for (Map<String, Object> override : expired) {
            UUID participantId = (UUID) override.get("participant_id");
            UUID id = (UUID) override.get("id");
            repository.timeline(participantId, override.get("action_name").toString(), string(override.get("target_type")),
                    (UUID) override.get("target_id"), "BREAK_GLASS_EXPIRED", "system",
                    "API-triggered break-glass expiry", Map.of("breakGlassId", id));
            outboxRepository.insert("BREAK_GLASS_CAPABILITY", id, participantId,
                    "BREAK_GLASS_CAPABILITY_EXPIRED", Map.of("breakGlassId", id));
        }
        return Map.of("expired", expired.size(), "status", "SUCCEEDED");
    }

    public List<Map<String, Object>> breakGlassActions() {
        return repository.breakGlassActions();
    }

    public List<Map<String, Object>> timeline(UUID participantId) {
        return repository.timeline(participantId);
    }

    private void evaluateAccount(Map<String, Object> participant, List<Map<String, Object>> denyReasons) {
        String accountStatus = participant.get("account_status").toString();
        if ("CLOSED".equals(accountStatus)) {
            denyReasons.add(reason("ACCOUNT_CLOSED", "Closed accounts cannot perform marketplace actions",
                    "accountStatus", accountStatus, "not CLOSED", "CRITICAL"));
        } else if ("SUSPENDED".equals(accountStatus)) {
            denyReasons.add(reason("ACCOUNT_SUSPENDED", "Suspended accounts require scoped break-glass to act",
                    "accountStatus", accountStatus, "not SUSPENDED", "CRITICAL"));
        } else if ("RESTRICTED".equals(accountStatus)) {
            denyReasons.add(reason("ACTIVE_RESTRICTION", "Restricted accounts require targeted policy review",
                    "accountStatus", accountStatus, "ACTIVE or LIMITED", "HIGH"));
        }
    }

    private void evaluateTrustTier(Map<String, Object> policy, Map<String, Object> participant,
                                   List<Map<String, Object>> denyReasons) {
        String requiredTier = string(policy.get("min_trust_tier"));
        if (requiredTier == null) {
            return;
        }
        String current = participant.get("trust_tier").toString();
        if (rank(TRUST_ORDER, current) < rank(TRUST_ORDER, requiredTier)) {
            denyReasons.add(reason("TRUST_TIER_TOO_LOW", "Trust tier is below the action policy minimum",
                    "trustTier", current, requiredTier, "MEDIUM"));
        }
    }

    private void evaluateVerification(Map<String, Object> policy, Map<String, Object> participant,
                                      List<Map<String, Object>> denyReasons) {
        String required = string(policy.get("required_verification_status"));
        if (required == null) {
            return;
        }
        String current = participant.get("verification_status").toString();
        if (rank(VERIFICATION_ORDER, current) < rank(VERIFICATION_ORDER, required)) {
            denyReasons.add(reason("VERIFICATION_REQUIRED", "Verification status is below the action policy minimum",
                    "verificationStatus", current, required, "HIGH"));
        }
    }

    private void evaluateRisk(Map<String, Object> policy, Map<String, Object> participant,
                              List<Map<String, Object>> denyReasons) {
        String maxRisk = string(policy.get("max_risk_level"));
        if (maxRisk == null) {
            return;
        }
        String current = participant.get("risk_level").toString();
        if (rank(RISK_ORDER, current) > rank(RISK_ORDER, maxRisk)) {
            denyReasons.add(reason("RISK_LEVEL_TOO_HIGH", "Participant risk level exceeds action policy limit",
                    "riskLevel", current, maxRisk, "HIGH"));
        }
    }

    private void evaluateCapabilities(Map<String, Object> policy, String actionName, List<String> capabilities,
                                      List<Map<String, Object>> denyReasons) {
        if (!Boolean.TRUE.equals(policy.get("requires_active_capability"))) {
            return;
        }
        List<String> allowed = capabilitiesFor(actionName);
        if (allowed.stream().noneMatch(capabilities::contains)) {
            denyReasons.add(reason("CAPABILITY_MISSING", "No active participant capability matches the marketplace action",
                    "capabilities", capabilities, allowed, "HIGH"));
        }
    }

    private void evaluateRestrictions(Map<String, Object> policy, String actionName, List<Map<String, Object>> restrictions,
                                      Long valueCents, List<Map<String, Object>> denyReasons) {
        if (!Boolean.TRUE.equals(policy.get("requires_no_active_restriction"))) {
            return;
        }
        for (Map<String, Object> restriction : restrictions) {
            String type = restriction.get("restriction_type").toString();
            if (blocksAction(type, actionName)) {
                denyReasons.add(reason(codeForRestriction(type), "Active restriction blocks this marketplace action",
                        "restrictionType", type, "no active " + type, "HIGH"));
            }
            if ("MAX_TRANSACTION_VALUE".equals(type) && valueCents != null && restriction.get("max_transaction_value_cents") instanceof Number max
                    && valueCents > max.longValue()) {
                denyReasons.add(reason("VALUE_ABOVE_LIMIT", "Action value is above participant restriction limit",
                        "valueCents", valueCents, max.longValue(), "HIGH"));
            }
        }
    }

    private void evaluateValue(Map<String, Object> policy, Long valueCents, List<Map<String, Object>> denyReasons) {
        if (valueCents == null || !(policy.get("max_value_cents") instanceof Number max)) {
            return;
        }
        if (valueCents > max.longValue()) {
            denyReasons.add(reason("VALUE_ABOVE_LIMIT", "Action value is above capability policy limit",
                    "valueCents", valueCents, max.longValue(), "HIGH"));
        }
    }

    private void evaluateTarget(String actionName, Map<String, Object> target, List<Map<String, Object>> denyReasons) {
        if (target.isEmpty()) {
            return;
        }
        if ("RECEIVE_SEARCH_EXPOSURE".equals(actionName)) {
            if (!"LIVE".equals(string(target.get("status")))) {
                denyReasons.add(reason("LISTING_NOT_LIVE", "Listing must be live for search exposure",
                        "listingStatus", target.get("status"), "LIVE", "HIGH"));
            }
            if (!Boolean.TRUE.equals(target.get("searchable"))) {
                denyReasons.add(reason("SEARCH_VISIBILITY_SUPPRESSED", "Search projection suppresses this listing",
                        "searchable", target.get("searchable"), true, "HIGH"));
            }
        }
        if ("ACCEPT_TRANSACTION".equals(actionName) && !"LIVE".equals(string(target.get("status")))) {
            denyReasons.add(reason("TARGET_NOT_ELIGIBLE", "Listing target is not live",
                    "listingStatus", target.get("status"), "LIVE", "HIGH"));
        }
        if ("CREATE_REVIEW".equals(actionName) && !"COMPLETED".equals(string(target.get("status")))) {
            denyReasons.add(reason("REVIEW_NOT_ELIGIBLE", "Reviews require a completed transaction",
                    "transactionStatus", target.get("status"), "COMPLETED", "HIGH"));
        }
        if ("REQUEST_PAYMENT_RELEASE".equals(actionName)) {
            if (!"COMPLETED".equals(string(target.get("status")))) {
                denyReasons.add(reason("TRANSACTION_NOT_COMPLETED", "Payment release requires a completed transaction",
                        "transactionStatus", target.get("status"), "COMPLETED", "HIGH"));
            }
            if (Boolean.TRUE.equals(target.get("unresolved_dispute"))) {
                denyReasons.add(reason("DISPUTE_UNRESOLVED", "Payment release requires no unresolved dispute",
                        "unresolvedDispute", true, false, "HIGH"));
            }
        }
    }

    private String decision(String actionName, List<Map<String, Object>> denyReasons, boolean temporaryGrant, boolean breakGlass) {
        if (denyReasons.isEmpty()) {
            return "ALLOW";
        }
        boolean closed = hasReason(denyReasons, "ACCOUNT_CLOSED");
        boolean suspended = hasReason(denyReasons, "ACCOUNT_SUSPENDED");
        boolean paymentReleaseBlocked = "REQUEST_PAYMENT_RELEASE".equals(actionName)
                && (hasReason(denyReasons, "TRANSACTION_NOT_COMPLETED") || hasReason(denyReasons, "DISPUTE_UNRESOLVED"));
        if (breakGlass && !closed && !paymentReleaseBlocked) {
            return "ALLOW_WITH_BREAK_GLASS";
        }
        if (temporaryGrant && !closed && !suspended) {
            return "ALLOW_WITH_TEMPORARY_GRANT";
        }
        if (denyReasons.stream().anyMatch(reason -> "VERIFICATION_REQUIRED".equals(reason.get("code")))) {
            return "REQUIRE_VERIFICATION";
        }
        if (denyReasons.stream().anyMatch(reason -> "ACTIVE_RESTRICTION".equals(reason.get("code"))
                || "CAPABILITY_RESTRICTED".equals(reason.get("code")))) {
            return "REQUIRE_MANUAL_REVIEW";
        }
        return "DENY";
    }

    private List<Map<String, Object>> nextSteps(List<Map<String, Object>> denyReasons) {
        return denyReasons.stream()
                .map(reason -> Map.of("forReason", reason.get("code"), "step", step(reason.get("code").toString())))
                .distinct()
                .toList();
    }

    private String step(String code) {
        return switch (code) {
            case "ACCOUNT_SUSPENDED" -> "request a scoped break-glass review for the exact emergency action";
            case "ACCOUNT_CLOSED" -> "no action is available for a closed account";
            case "TRUST_TIER_TOO_LOW" -> "complete low-risk marketplace transactions to raise trust tier";
            case "VERIFICATION_REQUIRED" -> "complete the required verification workflow";
            case "RISK_LEVEL_TOO_HIGH" -> "request manual risk review after resolving high-risk signals";
            case "VALUE_ABOVE_LIMIT" -> "reduce the action value or request a targeted temporary grant";
            case "CAPABILITY_MISSING" -> "request the exact marketplace capability needed for this action";
            case "CAPABILITY_RESTRICTED", "ACTIVE_RESTRICTION" -> "resolve or appeal the exact active restriction";
            case "LISTING_NOT_LIVE" -> "fix listing moderation state and publish the listing";
            case "TRANSACTION_NOT_COMPLETED" -> "complete the transaction before requesting payment release";
            case "DISPUTE_UNRESOLVED" -> "resolve the active dispute before requesting payment release";
            case "SEARCH_VISIBILITY_SUPPRESSED" -> "resolve the search suppression or hidden listing restriction";
            case "REVIEW_NOT_ELIGIBLE" -> "complete an eligible transaction before creating a review";
            default -> "request targeted manual review with evidence for this action";
        };
    }

    private boolean hasReason(List<Map<String, Object>> denyReasons, String code) {
        return denyReasons.stream().anyMatch(reason -> code.equals(reason.get("code")));
    }

    private List<String> reasonCodes(List<Map<String, Object>> reasons) {
        return reasons.stream().map(reason -> reason.get("code").toString()).sorted().toList();
    }

    private Map<String, Object> reason(String code, String message, String field, Object current, Object required, String severity) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("code", code);
        reason.put("message", message);
        reason.put("blockingField", field);
        reason.put("currentValue", current);
        reason.put("requiredValue", required);
        reason.put("severity", severity);
        return reason;
    }

    private boolean blocksAction(String restriction, String actionName) {
        return switch (restriction) {
            case "LISTING_BLOCKED" -> List.of("PUBLISH_LISTING", "RECEIVE_SEARCH_EXPOSURE").contains(actionName);
            case "ACCEPTING_BLOCKED" -> "ACCEPT_TRANSACTION".equals(actionName);
            case "REQUIRES_MANUAL_REVIEW", "REQUIRES_VERIFICATION" -> true;
            case "HIDDEN_FROM_MARKETPLACE_SEARCH" -> "RECEIVE_SEARCH_EXPOSURE".equals(actionName);
            case "REVIEW_WEIGHT_SUPPRESSED" -> "CREATE_REVIEW".equals(actionName);
            default -> false;
        };
    }

    private String codeForRestriction(String restriction) {
        return switch (restriction) {
            case "HIDDEN_FROM_MARKETPLACE_SEARCH" -> "SEARCH_VISIBILITY_SUPPRESSED";
            case "REVIEW_WEIGHT_SUPPRESSED" -> "REVIEW_NOT_ELIGIBLE";
            case "REQUIRES_VERIFICATION" -> "VERIFICATION_REQUIRED";
            case "LISTING_BLOCKED", "ACCEPTING_BLOCKED" -> "CAPABILITY_RESTRICTED";
            default -> "ACTIVE_RESTRICTION";
        };
    }

    private List<String> capabilitiesFor(String actionName) {
        return switch (actionName) {
            case "OPEN_DISPUTE", "CREATE_REVIEW" -> List.of("BUY", "SELL_ITEMS", "OFFER_SERVICES", "ACCEPT_ERRANDS", "ACCEPT_SHOPPING_REQUESTS");
            case "PUBLISH_LISTING", "ACCEPT_TRANSACTION", "RECEIVE_SEARCH_EXPOSURE", "REQUEST_PAYMENT_RELEASE" ->
                    List.of("SELL_ITEMS", "OFFER_SERVICES", "ACCEPT_ERRANDS", "ACCEPT_SHOPPING_REQUESTS");
            default -> List.of();
        };
    }

    private Map<String, Object> defaultPolicy(String actionName) {
        return Map.of(
                "action_name", actionName,
                "policy_name", "capability_policy",
                "policy_version", "capability_policy_v1",
                "requires_no_active_restriction", true,
                "requires_active_capability", true
        );
    }

    private Map<String, Object> publicParticipantSnapshot(Map<String, Object> participant) {
        return Map.of(
                "accountStatus", participant.get("account_status"),
                "verificationStatus", participant.get("verification_status"),
                "trustTier", participant.get("trust_tier"),
                "riskLevel", participant.get("risk_level"),
                "trustScore", participant.get("trust_score"),
                "trustConfidence", participant.get("trust_confidence")
        );
    }

    private int rank(List<String> order, String value) {
        int rank = order.indexOf(value);
        return rank < 0 ? 0 : rank;
    }

    private Long valueCents(Map<String, Object> request, Map<String, Object> target) {
        Object requested = request.get("valueCents");
        if (requested instanceof Number number) {
            return number.longValue();
        }
        return target.entrySet().stream()
                .filter(entry -> List.of("value_amount_cents", "price_amount_cents", "budget_amount_cents").contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private String string(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
