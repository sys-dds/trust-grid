package com.trustgrid.api.risk;

import com.trustgrid.api.idempotency.IdempotencyService;
import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiskDecisionService {

    private final RiskDecisionRepository repository;
    private final RiskRuleEvaluator evaluator;
    private final IdempotencyService idempotencyService;
    private final OutboxRepository outboxRepository;

    public RiskDecisionService(RiskDecisionRepository repository, RiskRuleEvaluator evaluator,
                               IdempotencyService idempotencyService, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.idempotencyService = idempotencyService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public RiskDecisionResponse record(RiskTargetType targetType, UUID targetId, List<String> reasons, Map<String, Object> snapshot) {
        RiskDecision decision = evaluator.decisionFor(reasons);
        String level = evaluator.levelFor(reasons);
        UUID id = repository.insert(targetType, targetId, score(level, reasons), level, decision, reasons, snapshot, "risk_rules_v1");
        outboxRepository.insert("RISK_DECISION", id, null, "RISK_DECISION_RECORDED",
                Map.of("targetType", targetType.name(), "targetId", targetId, "decision", decision.name()));
        return get(id);
    }

    @Transactional
    public RiskDecisionResponse reportOffPlatform(UUID transactionId, String idempotencyKey, OffPlatformContactReportRequest request) {
        return idempotencyService.run("risk:off-platform:" + transactionId, idempotencyKey,
                Map.of("transactionId", transactionId), request, "RISK_DECISION", this::get, () -> {
                    if (!repository.transactionParticipant(transactionId, request.reporterParticipantId())
                            || !repository.transactionParticipant(transactionId, request.reportedParticipantId())) {
                        throw new MarketplaceActionForbiddenException("Report participants must belong to transaction");
                    }
                    UUID reportId = repository.insertOffPlatformReport(transactionId, request);
                    RiskDecisionResponse decision = record(RiskTargetType.TRANSACTION, transactionId,
                            List.of("off_platform_contact_attempt", "manual_review_recommended"),
                            Map.of("reportId", reportId, "reportedParticipantId", request.reportedParticipantId()));
                    outboxRepository.insert("RISK_DECISION", decision.riskDecisionId(), request.reporterParticipantId(),
                            "OFF_PLATFORM_CONTACT_REPORTED", Map.of("transactionId", transactionId, "reportId", reportId));
                    outboxRepository.insert("RISK_DECISION", decision.riskDecisionId(), request.reporterParticipantId(),
                            "RISK_ACTION_RECOMMENDED", Map.of("recommendedAction", decision.decision().name()));
                    return decision.riskDecisionId();
                });
    }

    @Transactional
    public RiskDecisionResponse syntheticSignal(UUID participantId, String idempotencyKey, SyntheticRiskSignalRequest request) {
        return idempotencyService.run("risk:synthetic-signal:" + participantId, idempotencyKey,
                Map.of("participantId", participantId), request, "RISK_DECISION", this::get, () -> {
                    UUID signalId = repository.insertSyntheticSignal(participantId, request);
                    List<String> reasons = repository.repeatDisputes(participantId) >= 2
                            ? evaluator.participantRules(repository.repeatDisputes(participantId), request.riskWeight())
                            : evaluator.participantRules(0, request.riskWeight());
                    RiskDecisionResponse decision = record(RiskTargetType.PARTICIPANT, participantId, reasons,
                            Map.of("signalId", signalId, "simulation", true));
                    outboxRepository.insert("RISK_DECISION", decision.riskDecisionId(), participantId,
                            "RISK_ACTION_RECOMMENDED", Map.of("recommendedAction", decision.decision().name()));
                    return decision.riskDecisionId();
                });
    }

    public RiskDecisionResponse get(UUID riskDecisionId) {
        return repository.find(riskDecisionId).orElseThrow(() -> new NotFoundException("Risk decision not found"));
    }

    public List<RiskDecisionResponse> search(RiskTargetType targetType, UUID targetId) {
        return repository.search(targetType, targetId);
    }

    public RiskExplanationResponse explain(RiskTargetType targetType, UUID targetId) {
        List<RiskDecisionResponse> decisions = search(targetType, targetId);
        RiskDecisionResponse latest = decisions.stream()
                .max(Comparator.comparing(RiskDecisionResponse::createdAt))
                .orElseGet(() -> record(targetType, targetId, defaultRules(targetType), Map.of("generated", true)));
        return new RiskExplanationResponse(targetType, targetId, latest.riskLevel(), latest.decision(),
                latest.reasons(), evaluator.nextSteps(latest.decision()), latest.policyVersion());
    }

    private List<String> defaultRules(RiskTargetType targetType) {
        List<String> rules = new ArrayList<>();
        if (targetType == RiskTargetType.LISTING) {
            rules.add("listing_visibility_checked");
        }
        return rules;
    }

    private int score(String level, List<String> reasons) {
        int base = switch (level) {
            case "CRITICAL" -> 95;
            case "HIGH" -> 75;
            case "MEDIUM" -> 45;
            default -> 10;
        };
        return Math.min(100, base + reasons.size());
    }
}
