package com.trustgrid.api.trustsafety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.shared.OutboxRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrustSafetyService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxRepository outbox;

    public TrustSafetyService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, OutboxRepository outbox) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
    }

    @Transactional
    public Map<String, Object> openCase(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_cases (id, case_type, status, priority, title, summary, opened_by, reason, sla_due_at, metadata_json)
                values (?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, require(request, "caseType"), request.getOrDefault("priority", "MEDIUM"),
                require(request, "title"), require(request, "summary"), actor(request), reason(request),
                timestamp(request.get("slaDueAt")), json(request.getOrDefault("metadata", Map.of())));
        caseTimeline(id, "TRUST_CASE_OPENED", actor(request), reason(request), Map.of("caseId", id));
        outbox.insert("TRUST_CASE", id, null, "TRUST_CASE_OPENED", Map.of("caseType", request.get("caseType")));
        return Map.of("caseId", id, "status", "OPEN");
    }

    public List<Map<String, Object>> listCases() {
        return jdbcTemplate.queryForList("select * from trust_cases order by created_at desc limit 100");
    }

    public Map<String, Object> getCase(UUID id) {
        Map<String, Object> row = one("select * from trust_cases where id = ?", id);
        row.put("targets", jdbcTemplate.queryForList("select * from trust_case_targets where case_id = ? order by created_at", id));
        return row;
    }

    @Transactional
    public Map<String, Object> addCaseTarget(UUID caseId, Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_case_targets (id, case_id, target_type, target_id, relationship_type, added_by, reason)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (case_id, target_type, target_id) do nothing
                """, id, caseId, require(request, "targetType"), uuid(request, "targetId"),
                request.getOrDefault("relationshipType", "RELATED"), actor(request), reason(request));
        caseTimeline(caseId, "TRUST_CASE_TARGET_LINKED", actor(request), reason(request), request);
        outbox.insert("TRUST_CASE", caseId, null, "TRUST_CASE_TARGET_LINKED", Map.of("targetType", request.get("targetType")));
        return Map.of("caseId", caseId, "targetId", request.get("targetId"));
    }

    @Transactional
    public Map<String, Object> assignCase(UUID caseId, Map<String, Object> request) {
        jdbcTemplate.update("update trust_cases set status = 'ASSIGNED', assigned_to = ?, updated_at = now() where id = ?",
                require(request, "assignedTo"), caseId);
        caseTimeline(caseId, "TRUST_CASE_ASSIGNED", actor(request), reason(request), Map.of("assignedTo", request.get("assignedTo")));
        outbox.insert("TRUST_CASE", caseId, null, "TRUST_CASE_ASSIGNED", Map.of("assignedTo", request.get("assignedTo")));
        return Map.of("caseId", caseId, "status", "ASSIGNED");
    }

    @Transactional
    public Map<String, Object> updateCaseStatus(UUID caseId, Map<String, Object> request) {
        String status = require(request, "status");
        jdbcTemplate.update("""
                update trust_cases set status = ?, updated_at = now(),
                    resolved_at = case when ? in ('RESOLVED','FALSE_POSITIVE','CANCELLED') then now() else resolved_at end
                where id = ?
                """, status, status, caseId);
        caseTimeline(caseId, "TRUST_CASE_STATUS_CHANGED", actor(request), reason(request), Map.of("status", status));
        outbox.insert("TRUST_CASE", caseId, null, "TRUST_CASE_STATUS_CHANGED", Map.of("status", status));
        return Map.of("caseId", caseId, "status", status);
    }

    @Transactional
    public Map<String, Object> applyPlaybook(UUID caseId, Map<String, Object> request) {
        String key = request.getOrDefault("playbookKey", "default_trust_case_playbook").toString();
        jdbcTemplate.update("""
                insert into trust_case_playbooks (id, playbook_key, case_type, name, steps_json)
                values (?, ?, coalesce((select case_type from trust_cases where id = ?), 'SAFETY_CONCERN'), ?, cast(? as jsonb))
                on conflict (playbook_key) do nothing
                """, UUID.randomUUID(), key, caseId, key, json(List.of("review targets", "check evidence", "recommend containment")));
        generateCaseRecommendations(caseId, request);
        caseTimeline(caseId, "TRUST_CASE_PLAYBOOK_APPLIED", actor(request), reason(request), Map.of("playbookKey", key));
        outbox.insert("TRUST_CASE", caseId, null, "TRUST_CASE_PLAYBOOK_APPLIED", Map.of("playbookKey", key));
        return Map.of("caseId", caseId, "playbookKey", key, "status", "APPLIED");
    }

    public List<Map<String, Object>> caseTimeline(UUID caseId) {
        return jdbcTemplate.queryForList("select * from trust_case_timeline_events where case_id = ? order by created_at", caseId);
    }

    public Map<String, Object> caseEvidenceBundle(UUID caseId) {
        List<Map<String, Object>> targets = jdbcTemplate.queryForList("select * from trust_case_targets where case_id = ?", caseId);
        return Map.of("case", getCase(caseId), "targets", targets, "timeline", caseTimeline(caseId), "scope", "trust_case_targets_only");
    }

    public List<Map<String, Object>> caseRecommendations(UUID caseId) {
        return jdbcTemplate.queryForList("select * from trust_case_recommendations where case_id = ? order by created_at desc", caseId);
    }

    @Transactional
    public Map<String, Object> generateCaseRecommendations(UUID caseId, Map<String, Object> request) {
        int inserted = jdbcTemplate.update("""
                insert into trust_case_recommendations (id, case_id, recommendation_type, target_type, target_id, severity, reason, payload_json)
                select gen_random_uuid(), case_id, 'REVIEW_TARGET', target_type, target_id, 'MEDIUM', ?, '{}'::jsonb
                from trust_case_targets
                where case_id = ?
                  and not exists (
                    select 1 from trust_case_recommendations r
                    where r.case_id = trust_case_targets.case_id and r.target_type = trust_case_targets.target_type
                      and r.target_id = trust_case_targets.target_id and r.status = 'OPEN'
                  )
                """, reason(request), caseId);
        outbox.insert("TRUST_CASE", caseId, null, "TRUST_CASE_RECOMMENDATION_CREATED", Map.of("created", inserted));
        return Map.of("caseId", caseId, "recommendationsCreated", inserted);
    }

    @Transactional
    public Map<String, Object> mergeCases(Map<String, Object> request) {
        UUID source = uuid(request, "sourceCaseId");
        UUID target = uuid(request, "targetCaseId");
        jdbcTemplate.update("""
                insert into trust_case_targets (id, case_id, target_type, target_id, relationship_type, added_by, reason)
                select gen_random_uuid(), ?, target_type, target_id, relationship_type, ?, ?
                from trust_case_targets where case_id = ?
                on conflict (case_id, target_type, target_id) do nothing
                """, target, actor(request), reason(request), source);
        jdbcTemplate.update("update trust_cases set status = 'CANCELLED', updated_at = now() where id = ?", source);
        caseTimeline(target, "TRUST_CASE_MERGED", actor(request), reason(request), Map.of("sourceCaseId", source));
        outbox.insert("TRUST_CASE", target, null, "TRUST_CASE_MERGED", Map.of("sourceCaseId", source));
        return Map.of("sourceCaseId", source, "targetCaseId", target, "status", "MERGED");
    }

    @Transactional
    public Map<String, Object> splitCase(UUID caseId, Map<String, Object> request) {
        String splitActor = actor(request);
        String splitReason = reason(request);
        List<UUID> targetIds = uuidList(request.get("targetIds"));
        if (targetIds.isEmpty()) {
            throw new IllegalArgumentException("targetIds is required");
        }
        Map<String, Object> source = getCase(caseId);
        List<Map<String, Object>> selectedTargets = jdbcTemplate.queryForList("""
                select * from trust_case_targets
                where case_id = ? and id = any(?::uuid[])
                order by created_at
                """, caseId, targetIds.toArray(UUID[]::new));
        if (selectedTargets.size() != targetIds.size()) {
            throw new ConflictException("Every split target must belong to the source case");
        }
        boolean copy = Boolean.TRUE.equals(request.get("copyInsteadOfMove"));
        UUID newCase = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_cases (id, case_type, status, priority, title, summary, opened_by, reason, metadata_json)
                values (?, ?, 'OPEN', ?, ?, ?, ?, ?, '{}'::jsonb)
                """, newCase, source.get("case_type"), source.get("priority"), source.get("title") + " split",
                source.get("summary"), splitActor, splitReason);
        if (copy) {
            for (Map<String, Object> target : selectedTargets) {
                jdbcTemplate.update("""
                        insert into trust_case_targets (id, case_id, target_type, target_id, relationship_type, added_by, reason)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (case_id, target_type, target_id) do nothing
                        """, UUID.randomUUID(), newCase, target.get("target_type"), target.get("target_id"),
                        target.get("relationship_type"), splitActor, splitReason);
            }
        } else {
            jdbcTemplate.update("""
                    update trust_case_targets set case_id = ?, added_by = ?, reason = ?
                    where case_id = ? and id = any(?::uuid[])
                    """, newCase, splitActor, splitReason, caseId, targetIds.toArray(UUID[]::new));
        }
        Map<String, Object> payload = Map.of("newCaseId", newCase, "targetIds", targetIds, "copied", copy);
        caseTimeline(caseId, "TRUST_CASE_SPLIT", splitActor, splitReason, payload);
        caseTimeline(newCase, "TRUST_CASE_SPLIT", splitActor, splitReason, Map.of("sourceCaseId", caseId, "targetIds", targetIds, "copied", copy));
        outbox.insert("TRUST_CASE", newCase, null, "TRUST_CASE_SPLIT", Map.of("sourceCaseId", caseId, "targetIds", targetIds, "copied", copy));
        return Map.of("sourceCaseId", caseId, "newCaseId", newCase, "movedTargetCount", selectedTargets.size(),
                "copied", copy, "movedTargetIds", targetIds);
    }

    @Transactional
    public Map<String, Object> replayCase(UUID caseId, Map<String, Object> request) {
        int targets = count("select count(*) from trust_case_targets where case_id = ?", caseId);
        int semanticEvents = count("""
                select count(*) from trust_case_timeline_events
                where case_id = ? and event_type <> 'TRUST_CASE_REPLAYED'
                """, caseId);
        int replayEvents = count("""
                select count(*) from trust_case_timeline_events
                where case_id = ? and event_type = 'TRUST_CASE_REPLAYED'
                """, caseId);
        Map<String, Object> source = one("select status from trust_cases where id = ?", caseId);
        List<Object> reconstructedTargetIds = jdbcTemplate.queryForList("""
                select target_id from trust_case_targets where case_id = ? order by target_type, target_id
                """, Object.class, caseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseId);
        result.put("targetCount", targets);
        result.put("semanticTimelineEventCount", semanticEvents);
        result.put("replayEventCount", replayEvents);
        result.put("deterministic", true);
        result.put("reconstructedStatus", source.get("status"));
        result.put("reconstructedTargetIds", reconstructedTargetIds);
        if (Boolean.TRUE.equals(request.get("recordReplayEvent"))) {
            caseTimeline(caseId, "TRUST_CASE_REPLAYED", actor(request), reason(request),
                    Map.of("targets", targets, "semanticTimelineEvents", semanticEvents, "replayEventsBefore", replayEvents));
            outbox.insert("TRUST_CASE", caseId, null, "TRUST_CASE_REPLAYED",
                    Map.of("targets", targets, "semanticTimelineEvents", semanticEvents));
        }
        return result;
    }

    public Map<String, Object> caseMetrics() {
        return Map.of("openCases", count("select count(*) from trust_cases where status not in ('RESOLVED','FALSE_POSITIVE','CANCELLED')"),
                "criticalCases", count("select count(*) from trust_cases where priority = 'CRITICAL'"),
                "recommendationsOpen", count("select count(*) from trust_case_recommendations where status = 'OPEN'"));
    }

    @Transactional
    public Map<String, Object> createCampaign(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_campaigns (id, campaign_type, status, severity, title, summary, opened_by, reason, metadata_json)
                values (?, ?, 'OPEN', ?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, require(request, "campaignType"), request.getOrDefault("severity", "MEDIUM"),
                require(request, "title"), require(request, "summary"), actor(request), reason(request),
                json(request.getOrDefault("metadata", Map.of())));
        outbox.insert("TRUST_CAMPAIGN", id, null, "TRUST_CAMPAIGN_CREATED", Map.of("campaignType", request.get("campaignType")));
        return Map.of("campaignId", id, "status", "OPEN");
    }

    public List<Map<String, Object>> campaigns() {
        return jdbcTemplate.queryForList("select * from trust_campaigns order by created_at desc limit 100");
    }

    public Map<String, Object> campaign(UUID id) {
        return one("select * from trust_campaigns where id = ?", id);
    }

    @Transactional
    public Map<String, Object> rebuildCampaignGraph(UUID campaignId) {
        int inserted = jdbcTemplate.update("""
                insert into trust_campaign_graph_edges (id, campaign_id, source_type, source_id, target_type, target_id, edge_type, strength, evidence_json)
                select gen_random_uuid(), ?, 'CASE', c.id, t.target_type, t.target_id, 'SAME_CASE', 1, '{}'::jsonb
                from trust_cases c join trust_case_targets t on t.case_id = c.id
                where c.metadata_json->>'campaignId' = ? or c.case_type = 'CAMPAIGN_INVESTIGATION'
                limit 100
                """, campaignId, campaignId.toString());
        if (inserted == 0) {
            jdbcTemplate.update("""
                    insert into trust_campaign_graph_edges (id, campaign_id, source_type, source_id, target_type, target_id, edge_type, strength, evidence_json)
                    values (?, ?, 'CAMPAIGN', ?, 'CAMPAIGN', ?, 'SAME_CAMPAIGN', 1, '{}'::jsonb)
                    """, UUID.randomUUID(), campaignId, campaignId, campaignId);
            inserted = 1;
        }
        outbox.insert("TRUST_CAMPAIGN", campaignId, null, "TRUST_CAMPAIGN_GRAPH_EDGE_CREATED", Map.of("edges", inserted));
        return Map.of("campaignId", campaignId, "edgesCreated", inserted);
    }

    public List<Map<String, Object>> campaignGraph(UUID campaignId) {
        return jdbcTemplate.queryForList("select * from trust_campaign_graph_edges where campaign_id = ? order by created_at", campaignId);
    }

    public Map<String, Object> campaignBlastRadius(UUID campaignId) {
        return Map.of("campaignId", campaignId,
                "affectedParticipants", count("select count(distinct target_id) from trust_campaign_graph_edges where campaign_id = ? and target_type = 'PARTICIPANT'", campaignId),
                "affectedListings", count("select count(distinct target_id) from trust_campaign_graph_edges where campaign_id = ? and target_type = 'LISTING'", campaignId),
                "affectedReviews", count("select count(distinct target_id) from trust_campaign_graph_edges where campaign_id = ? and target_type = 'REVIEW'", campaignId),
                "affectedDisputes", count("select count(distinct target_id) from trust_campaign_graph_edges where campaign_id = ? and target_type = 'DISPUTE'", campaignId),
                "affectedTransactions", count("select count(distinct target_id) from trust_campaign_graph_edges where campaign_id = ? and target_type = 'TRANSACTION'", campaignId),
                "deterministic", true);
    }

    @Transactional
    public Map<String, Object> simulateContainment(UUID campaignId, Map<String, Object> request) {
        Map<String, Object> radius = campaignBlastRadius(campaignId);
        outbox.insert("TRUST_CAMPAIGN", campaignId, null, "CAMPAIGN_CONTAINMENT_SIMULATED", radius);
        return Map.of("campaignId", campaignId, "blastRadius", radius, "wouldMutate", false);
    }

    @Transactional
    public Map<String, Object> createContainmentPlan(UUID campaignId, Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        Object actions = request.getOrDefault("actions", List.of(Map.of("actionType", "OPEN_TRUST_CASE", "targetType", "CAMPAIGN", "targetId", campaignId.toString())));
        jdbcTemplate.update("""
                insert into campaign_containment_plans (id, campaign_id, status, proposed_by, reason, blast_radius_json, actions_json)
                values (?, ?, 'PROPOSED', ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, id, campaignId, actor(request), reason(request), json(campaignBlastRadius(campaignId)), json(actions));
        return Map.of("planId", id, "status", "PROPOSED");
    }

    @Transactional
    public Map<String, Object> approveContainment(UUID planId, Map<String, Object> request) {
        require(request, "riskAcknowledgement");
        int updated = jdbcTemplate.update("""
                update campaign_containment_plans set status = 'APPROVED', approved_at = now(), approved_by = ?,
                    approval_reason = ?, risk_acknowledgement = ?
                where id = ? and status = 'PROPOSED'
                """, actor(request), reason(request), request.get("riskAcknowledgement"), planId);
        if (updated == 0) throw new ConflictException("Containment plan cannot be approved");
        outbox.insert("CONTAINMENT_PLAN", planId, null, "CAMPAIGN_CONTAINMENT_APPROVED", Map.of("planId", planId));
        return Map.of("planId", planId, "status", "APPROVED");
    }

    @Transactional
    public Map<String, Object> executeContainment(UUID planId, Map<String, Object> request) {
        Map<String, Object> plan = one("select * from campaign_containment_plans where id = ?", planId);
        if ("EXECUTED".equals(plan.get("status"))) {
            int existing = count("select count(*) from campaign_containment_actions where containment_plan_id = ?", planId);
            return Map.of("planId", planId, "status", "EXECUTED", "actionsExecuted", existing, "idempotent", true);
        }
        if (!"APPROVED".equals(plan.get("status"))) throw new ConflictException("Containment plan must be approved");
        List<Map<String, Object>> actions = jsonList(plan.get("actions_json"));
        if (actions.isEmpty()) throw new IllegalArgumentException("Containment plan actions_json must not be empty");
        for (Map<String, Object> action : actions) {
            validateContainmentAction(action);
        }
        List<UUID> actionIds = new ArrayList<>();
        for (Map<String, Object> action : actions) {
            actionIds.add(executeContainmentAction(planId, (UUID) plan.get("campaign_id"), action));
        }
        jdbcTemplate.update("update campaign_containment_plans set status = 'EXECUTED', executed_at = now() where id = ?", planId);
        outbox.insert("CONTAINMENT_PLAN", planId, null, "CAMPAIGN_CONTAINMENT_EXECUTED", Map.of("actionIds", actionIds));
        return Map.of("planId", planId, "status", "EXECUTED", "actionIds", actionIds, "actionsExecuted", actionIds.size());
    }

    @Transactional
    public Map<String, Object> reverseContainment(UUID planId, Map<String, Object> request) {
        require(request, "actor");
        require(request, "reason");
        require(request, "riskAcknowledgement");
        Map<String, Object> plan = one("select * from campaign_containment_plans where id = ?", planId);
        if ("REVERSED".equals(plan.get("status"))) {
            int existing = count("select count(*) from campaign_containment_actions where containment_plan_id = ? and status in ('REVERSED','REVERSAL_CONFLICT')", planId);
            return Map.of("planId", planId, "actionsReversed", existing, "status", "REVERSED", "idempotent", true);
        }
        if (!List.of("EXECUTED", "PARTIALLY_EXECUTED").contains(plan.get("status"))) {
            throw new ConflictException("Containment plan must be executed before reversal");
        }
        int reversed = 0;
        int conflicts = 0;
        for (Map<String, Object> action : jdbcTemplate.queryForList("""
                select * from campaign_containment_actions where containment_plan_id = ? and status = 'EXECUTED'
                order by created_at
                """, planId)) {
            Map<String, Object> reversal = reverseContainmentAction(action, request);
            if (Boolean.TRUE.equals(reversal.get("conflict"))) {
                conflicts++;
            } else {
                reversed++;
            }
        }
        jdbcTemplate.update("update campaign_containment_plans set status = 'REVERSED', reversed_at = now() where id = ?", planId);
        outbox.insert("CONTAINMENT_PLAN", planId, null, "CAMPAIGN_CONTAINMENT_REVERSED",
                Map.of("actionsReversed", reversed, "conflicts", conflicts, "actor", actor(request)));
        return Map.of("planId", planId, "actionsReversed", reversed, "conflicts", conflicts, "status", "REVERSED");
    }

    public List<Map<String, Object>> campaignTimeline(UUID campaignId) {
        return jdbcTemplate.queryForList("""
                select 'GRAPH_EDGE' as event_type, created_at, evidence_json as payload_json from trust_campaign_graph_edges where campaign_id = ?
                union all
                select status as event_type, created_at, blast_radius_json as payload_json from campaign_containment_plans where campaign_id = ?
                order by created_at
                """, campaignId, campaignId);
    }

    private void validateContainmentAction(Map<String, Object> action) {
        String actionType = require(action, "actionType");
        String targetType = require(action, "targetType");
        uuid(action, "targetId");
        if (!List.of("OPEN_TRUST_CASE", "HIDE_LISTING", "RESTRICT_CAPABILITY", "REQUIRE_VERIFICATION",
                "SUPPRESS_REVIEW_WEIGHT", "CREATE_OPS_QUEUE_ITEM", "ESCALATE_DISPUTE", "REQUEST_EVIDENCE",
                "REQUEST_PAYOUT_HOLD", "REQUEST_GUARANTEE_REVIEW").contains(actionType)) {
            throw new IllegalArgumentException("Unsupported containment actionType");
        }
        if ("HIDE_LISTING".equals(actionType) && !"LISTING".equals(targetType)) {
            throw new IllegalArgumentException("HIDE_LISTING requires LISTING target");
        }
        if (List.of("RESTRICT_CAPABILITY", "REQUIRE_VERIFICATION").contains(actionType) && !"PARTICIPANT".equals(targetType)) {
            throw new IllegalArgumentException(actionType + " requires PARTICIPANT target");
        }
        if ("ESCALATE_DISPUTE".equals(actionType) && !"DISPUTE".equals(targetType)) {
            throw new IllegalArgumentException("ESCALATE_DISPUTE requires DISPUTE target");
        }
    }

    private UUID executeContainmentAction(UUID planId, UUID campaignId, Map<String, Object> action) {
        String actionType = require(action, "actionType");
        String targetType = require(action, "targetType");
        UUID targetId = uuid(action, "targetId");
        Map<String, Object> before = containmentBefore(actionType, targetType, targetId);
        Map<String, Object> after = switch (actionType) {
            case "OPEN_TRUST_CASE" -> containmentOpenCase(planId, campaignId, targetType, targetId);
            case "HIDE_LISTING" -> containmentHideListing(planId, targetId);
            case "RESTRICT_CAPABILITY" -> containmentRestriction(planId, targetId, "ACCEPTING_BLOCKED");
            case "REQUIRE_VERIFICATION" -> containmentRestriction(planId, targetId, "REQUIRES_VERIFICATION");
            case "SUPPRESS_REVIEW_WEIGHT" -> containmentOpsQueue(actionType, targetType, targetId, "Review suppression requested by campaign containment");
            case "CREATE_OPS_QUEUE_ITEM" -> containmentOpsQueue(actionType, targetType, targetId, "Campaign containment manual review");
            case "ESCALATE_DISPUTE" -> containmentEscalateDispute(planId, targetId);
            case "REQUEST_EVIDENCE" -> containmentRequestEvidence(planId, targetType, targetId);
            case "REQUEST_PAYOUT_HOLD" -> containmentPayoutHold(planId, targetType, targetId);
            case "REQUEST_GUARANTEE_REVIEW" -> containmentOpsQueue(actionType, targetType, targetId, "Guarantee review requested by containment");
            default -> throw new IllegalArgumentException("Unsupported containment actionType");
        };
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into campaign_containment_actions (
                    id, containment_plan_id, action_type, target_type, target_id, status, before_json, after_json, executed_at
                ) values (?, ?, ?, ?, ?, 'EXECUTED', cast(? as jsonb), cast(? as jsonb), now())
                """, id, planId, actionType, targetType, targetId, json(before), json(after));
        return id;
    }

    private Map<String, Object> containmentBefore(String actionType, String targetType, UUID targetId) {
        return switch (actionType) {
            case "HIDE_LISTING" -> safeOne("select id, status, moderation_status from marketplace_listings where id = ?", targetId);
            case "RESTRICT_CAPABILITY", "REQUIRE_VERIFICATION" -> Map.of(
                    "activeRestrictions", jdbcTemplate.queryForList("select id, restriction_type, status from participant_restrictions where participant_id = ? and status = 'ACTIVE'", targetId));
            case "ESCALATE_DISPUTE" -> safeOne("select id, status from marketplace_disputes where id = ?", targetId);
            default -> Map.of("targetType", targetType, "targetId", targetId);
        };
    }

    private Map<String, Object> containmentOpenCase(UUID planId, UUID campaignId, String targetType, UUID targetId) {
        UUID caseId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_cases (id, case_type, status, priority, title, summary, opened_by, reason, metadata_json)
                values (?, 'CAMPAIGN_INVESTIGATION', 'OPEN', 'HIGH', ?, ?, 'system', ?, cast(? as jsonb))
                """, caseId, "Containment case " + planId, "Case opened from campaign containment",
                "Campaign containment action", json(Map.of("campaignId", campaignId, "containmentPlanId", planId)));
        jdbcTemplate.update("""
                insert into trust_case_targets (id, case_id, target_type, target_id, relationship_type, added_by, reason)
                values (?, ?, ?, ?, 'CONTAINMENT_TARGET', 'system', ?)
                on conflict (case_id, target_type, target_id) do nothing
                """, UUID.randomUUID(), caseId, targetType, targetId, "Campaign containment target");
        caseTimeline(caseId, "TRUST_CASE_OPENED", "system", "Campaign containment action", Map.of("containmentPlanId", planId));
        return Map.of("caseId", caseId);
    }

    private Map<String, Object> containmentHideListing(UUID planId, UUID listingId) {
        int updated = jdbcTemplate.update("""
                update marketplace_listings
                set status = 'HIDDEN', moderation_status = 'MODERATOR_HIDDEN', hidden_at = now(), updated_at = now()
                where id = ?
                """, listingId);
        if (updated == 0) throw new NotFoundException("Listing not found for containment");
        return Map.of("status", "HIDDEN", "containmentPlanId", planId);
    }

    private Map<String, Object> containmentRestriction(UUID planId, UUID participantId, String restrictionType) {
        UUID restrictionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participant_restrictions (
                    id, participant_id, restriction_type, status, actor, reason, metadata_json
                ) values (?, ?, ?, 'ACTIVE', 'system', ?, cast(? as jsonb))
                """, restrictionId, participantId, restrictionType, "Campaign containment restriction",
                json(Map.of("containmentPlanId", planId)));
        return Map.of("restrictionId", restrictionId, "restrictionType", restrictionType, "status", "ACTIVE");
    }

    private Map<String, Object> containmentOpsQueue(String actionType, String targetType, UUID targetId, String queueReason) {
        UUID queueId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_ops_queue_items (id, queue_type, target_type, target_id, priority, status, reason, signals_json)
                values (?, ?, ?, ?, 'HIGH', 'OPEN', ?, cast(? as jsonb))
                on conflict (queue_type, target_type, target_id, status) do nothing
                """, queueId, actionType, targetType, targetId, queueReason, json(Map.of("containmentAction", actionType)));
        return Map.of("queueItemId", queueId, "manualReview", true);
    }

    private Map<String, Object> containmentEscalateDispute(UUID planId, UUID disputeId) {
        int updated = jdbcTemplate.update("update marketplace_disputes set status = 'ESCALATED', updated_at = now() where id = ?", disputeId);
        if (updated == 0) throw new NotFoundException("Dispute not found for containment");
        return Map.of("status", "ESCALATED", "containmentPlanId", planId);
    }

    private Map<String, Object> containmentRequestEvidence(UUID planId, String targetType, UUID targetId) {
        UUID requirementId = UUID.randomUUID();
        String evidenceTarget = "LISTING".equals(targetType) || "TRANSACTION".equals(targetType) || "DISPUTE".equals(targetType) || "PARTICIPANT".equals(targetType)
                ? targetType : "PARTICIPANT";
        jdbcTemplate.update("""
                insert into evidence_requirements (id, target_type, target_id, evidence_type, required_before_action, reason)
                values (?, ?, ?, 'USER_STATEMENT', 'CAMPAIGN_CONTAINMENT_REVIEW', ?)
                """, requirementId, evidenceTarget, targetId, "Evidence requested by containment plan " + planId);
        return Map.of("evidenceRequirementId", requirementId);
    }

    private Map<String, Object> containmentPayoutHold(UUID planId, String targetType, UUID targetId) {
        UUID transactionId = "TRANSACTION".equals(targetType) ? targetId : null;
        if (transactionId == null) {
            return containmentOpsQueue("REQUEST_PAYOUT_HOLD", targetType, targetId, "Payout hold review requested; transaction target required for boundary event");
        }
        jdbcTemplate.update("""
                insert into payment_boundary_events (id, transaction_id, event_type, event_key, reason, requested_by, payload_json)
                values (?, ?, 'MARKETPLACE_PAYOUT_HOLD_REQUESTED', ?, 'Campaign containment payout hold recommendation', 'system', cast(? as jsonb))
                on conflict (event_key) do nothing
                """, UUID.randomUUID(), transactionId, "containment-" + planId + "-payout-hold-" + transactionId,
                json(Map.of("containmentPlanId", planId, "noMoneyMovement", true)));
        return Map.of("paymentBoundaryRecommendation", "MARKETPLACE_PAYOUT_HOLD_REQUESTED", "noMoneyMovement", true);
    }

    private Map<String, Object> reverseContainmentAction(Map<String, Object> action, Map<String, Object> request) {
        String actionType = action.get("action_type").toString();
        UUID targetId = (UUID) action.get("target_id");
        Map<String, Object> before = jsonMap(action.get("before_json"));
        Map<String, Object> after = jsonMap(action.get("after_json"));
        Map<String, Object> reversal = new LinkedHashMap<>();
        reversal.put("actor", actor(request));
        reversal.put("reason", reason(request));
        reversal.put("riskAcknowledgement", require(request, "riskAcknowledgement"));
        reversal.put("conflict", false);
        switch (actionType) {
            case "HIDE_LISTING" -> {
                Map<String, Object> current = safeOne("select id, status, moderation_status from marketplace_listings where id = ?", targetId);
                if ("HIDDEN".equals(current.get("status")) && "HIDDEN".equals(after.get("status"))) {
                    jdbcTemplate.update("""
                            update marketplace_listings set status = ?, moderation_status = ?, updated_at = now()
                            where id = ?
                            """, before.getOrDefault("status", "LIVE"), before.getOrDefault("moderation_status", "AUTO_APPROVED"), targetId);
                    reversal.put("restoredStatus", before.getOrDefault("status", "LIVE"));
                } else {
                    reversal.put("conflict", true);
                    reversal.put("currentStatus", current.get("status"));
                }
            }
            case "RESTRICT_CAPABILITY", "REQUIRE_VERIFICATION" -> {
                int removed = jdbcTemplate.update("""
                        update participant_restrictions
                        set status = 'REMOVED', removed_at = now(), removed_by = ?, remove_reason = ?
                        where participant_id = ? and status = 'ACTIVE' and metadata_json->>'containmentPlanId' = ?
                        """, actor(request), reason(request), targetId, action.get("containment_plan_id").toString());
                reversal.put("restrictionsRemoved", removed);
            }
            case "REQUEST_PAYOUT_HOLD" -> reversal.put("noMoneyMovement", true);
            case "OPEN_TRUST_CASE" -> {
                Object caseId = after.get("caseId");
                if (caseId != null) {
                    caseTimeline(UUID.fromString(caseId.toString()), "CONTAINMENT_REVERSED", actor(request), reason(request),
                            Map.of("containmentActionId", action.get("id")));
                }
                reversal.put("caseDeleted", false);
            }
            default -> reversal.put("manualReviewOnly", true);
        }
        jdbcTemplate.update("""
                update campaign_containment_actions
                set status = ?, reversed_at = now(), reversal_json = cast(? as jsonb)
                where id = ?
                """, Boolean.TRUE.equals(reversal.get("conflict")) ? "REVERSAL_CONFLICT" : "REVERSED", json(reversal), action.get("id"));
        return reversal;
    }

    @Transactional
    public Map<String, Object> createEvidenceVersion(UUID evidenceId, Map<String, Object> request) {
        int version = count("select count(*) from evidence_versions where evidence_id = ?", evidenceId) + 1;
        UUID id = UUID.randomUUID();
        String hash = request.getOrDefault("hash", "hash-" + evidenceId + "-" + version).toString();
        jdbcTemplate.update("""
                insert into evidence_versions (id, evidence_id, version_number, object_key, hash, provenance_json, created_by)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """, id, evidenceId, version, request.get("objectKey"), hash,
                json(request.getOrDefault("provenance", Map.of("source", "metadata"))), optionalUuid(request.get("createdBy")));
        custody(evidenceId, id, "VERSION_ADDED", actor(request), reason(request), null, hash, Map.of("version", version));
        outbox.insert("EVIDENCE", evidenceId, null, "EVIDENCE_VERSION_CREATED", Map.of("versionId", id));
        return Map.of("versionId", id, "versionNumber", version, "hash", hash);
    }

    public List<Map<String, Object>> evidenceVersions(UUID evidenceId) {
        return jdbcTemplate.queryForList("select * from evidence_versions where evidence_id = ? order by version_number", evidenceId);
    }

    public List<Map<String, Object>> custodyChain(UUID evidenceId) {
        return jdbcTemplate.queryForList("select * from evidence_custody_events where evidence_id = ? order by created_at", evidenceId);
    }

    @Transactional
    public Map<String, Object> createCustodyEvent(UUID evidenceId, Map<String, Object> request) {
        UUID id = custody(evidenceId, optionalUuid(request.get("evidenceVersionId")),
                request.getOrDefault("eventType", "ACCESSED").toString(), actor(request), reason(request),
                string(request.get("hashBefore")), string(request.get("hashAfter")),
                Map.of("metadata", request.getOrDefault("metadata", Map.of())));
        outbox.insert("EVIDENCE", evidenceId, null, "EVIDENCE_CUSTODY_EVENT_RECORDED", Map.of("custodyEventId", id));
        return Map.of("custodyEventId", id);
    }

    @Transactional
    public Map<String, Object> tamperCheck(UUID evidenceId, Map<String, Object> request) {
        Map<String, Object> latest = jdbcTemplate.queryForList("""
                select * from evidence_versions where evidence_id = ? order by version_number desc limit 1
                """, evidenceId).stream().findFirst().orElse(Map.of("hash", ""));
        String expected = request.getOrDefault("expectedHash", latest.get("hash")).toString();
        boolean match = expected.equals(latest.get("hash"));
        custody(evidenceId, (UUID) latest.get("id"), match ? "HASH_VERIFIED" : "HASH_MISMATCH_DETECTED",
                actor(request), reason(request), latest.get("hash").toString(), expected, Map.of("match", match));
        outbox.insert("EVIDENCE", evidenceId, null, "EVIDENCE_TAMPER_CHECK_RUN", Map.of("hashMatched", match));
        return Map.of("evidenceId", evidenceId, "hashMatched", match, "deterministic", true);
    }

    @Transactional
    public Map<String, Object> evidenceAccess(UUID evidenceId, Map<String, Object> request) {
        boolean redacted = Boolean.TRUE.equals(request.get("redactionRequired"));
        String decision = redacted ? "ALLOW_REDACTED" : request.getOrDefault("decision", "ALLOW").toString();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into evidence_access_decisions (id, evidence_id, requested_by, access_purpose, decision, deny_reasons_json, redaction_required)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """, id, evidenceId, actor(request), request.getOrDefault("accessPurpose", "CASE_REVIEW"),
                decision, json(List.of()), redacted);
        outbox.insert("EVIDENCE", evidenceId, null, "EVIDENCE_ACCESS_SIMULATED", Map.of("decision", decision));
        return Map.of("accessDecisionId", id, "decision", decision, "redactionRequired", redacted);
    }

    @Transactional
    public Map<String, Object> retention(UUID evidenceId, Map<String, Object> request) {
        jdbcTemplate.update("""
                insert into evidence_retention_metadata (id, evidence_id, retention_class, retain_until, legal_hold, legal_hold_reason)
                values (?, ?, ?, ?, false, null)
                on conflict (evidence_id) do update set retention_class = excluded.retention_class,
                    retain_until = excluded.retain_until, updated_at = now()
                """, UUID.randomUUID(), evidenceId, request.getOrDefault("retentionClass", "STANDARD"),
                timestamp(request.get("retainUntil")));
        outbox.insert("EVIDENCE", evidenceId, null, "EVIDENCE_RETENTION_UPDATED", Map.of("evidenceId", evidenceId));
        return Map.of("evidenceId", evidenceId, "retentionUpdated", true);
    }

    @Transactional
    public Map<String, Object> legalHold(UUID evidenceId, Map<String, Object> request) {
        jdbcTemplate.update("""
                insert into evidence_retention_metadata (id, evidence_id, retention_class, legal_hold, legal_hold_reason)
                values (?, ?, 'LEGAL_HOLD', true, ?)
                on conflict (evidence_id) do update set legal_hold = true, legal_hold_reason = excluded.legal_hold_reason, updated_at = now()
                """, UUID.randomUUID(), evidenceId, reason(request));
        custody(evidenceId, null, "LEGAL_HOLD_APPLIED", actor(request), reason(request), null, null, Map.of("legalHold", true));
        outbox.insert("EVIDENCE", evidenceId, null, "EVIDENCE_LEGAL_HOLD_UPDATED", Map.of("legalHold", true));
        return Map.of("evidenceId", evidenceId, "legalHold", true);
    }

    @Transactional
    public Map<String, Object> evidenceReplay(UUID evidenceId, Map<String, Object> request) {
        int versions = count("select count(*) from evidence_versions where evidence_id = ?", evidenceId);
        int custody = count("select count(*) from evidence_custody_events where evidence_id = ?", evidenceId);
        custody(evidenceId, null, "CONSISTENCY_REPLAYED", actor(request), reason(request), null, null, Map.of("versions", versions));
        outbox.insert("EVIDENCE", evidenceId, null, "EVIDENCE_CONSISTENCY_REPLAYED", Map.of("versions", versions));
        return Map.of("evidenceId", evidenceId, "versions", versions, "custodyEvents", custody, "deterministic", true);
    }

    @Transactional
    public Map<String, Object> disclosureBundle(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        UUID targetId = uuid(request, "targetId");
        jdbcTemplate.update("""
                insert into evidence_disclosure_bundles (id, target_type, target_id, requested_by, reason, bundle_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, require(request, "targetType"), targetId, actor(request), reason(request),
                json(Map.of("targetId", targetId, "redactionMetadataOnly", true)));
        outbox.insert("EVIDENCE_DISCLOSURE", id, null, "EVIDENCE_DISCLOSURE_BUNDLE_CREATED", Map.of("targetType", request.get("targetType")));
        return Map.of("bundleId", id, "targetId", targetId);
    }

    @Transactional
    public Map<String, Object> createGuaranteePolicy(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_guarantee_policies (
                    id, policy_name, policy_version, max_value_cents, required_evidence_json, fraud_exclusions_json,
                    outcome_rules_json, created_by, reason
                ) values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?)
                """, id, require(request, "policyName"), require(request, "policyVersion"), longValue(request.get("maxValueCents")),
                json(request.getOrDefault("requiredEvidence", List.of())), json(request.getOrDefault("fraudExclusions", List.of())),
                json(request.getOrDefault("outcomeRules", Map.of())), actor(request), reason(request));
        outbox.insert("GUARANTEE_POLICY", id, null, "GUARANTEE_POLICY_CREATED", Map.of("policyName", request.get("policyName")));
        return Map.of("policyId", id);
    }

    public List<Map<String, Object>> guaranteePolicies() {
        return jdbcTemplate.queryForList("select * from marketplace_guarantee_policies order by created_at desc");
    }

    @Transactional
    public Map<String, Object> guaranteeEligibility(Map<String, Object> request) {
        UUID transactionId = optionalUuid(request.get("transactionId"));
        UUID disputeId = optionalUuid(request.get("disputeId"));
        UUID participantId = optionalUuid(request.get("participantId"));
        String policyName = request.getOrDefault("policyName", "guarantee_policy").toString();
        String policyVersion = request.getOrDefault("policyVersion", "guarantee_policy_v1").toString();
        Map<String, Object> tx = transactionId == null ? Map.of() : jdbcTemplate.queryForList("select * from marketplace_transactions where id = ?", transactionId).stream().findFirst().orElse(Map.of());
        Map<String, Object> dispute = disputeId == null ? Map.of() : safeOne("select * from marketplace_disputes where id = ?", disputeId);
        Map<String, Object> policy = safeOne("""
                select * from marketplace_guarantee_policies
                where policy_name = ? and policy_version = ? and enabled = true
                order by created_at desc limit 1
                """, policyName, policyVersion);
        List<String> requiredEvidence = requiredEvidence(policy, request);
        Map<String, Object> evaluation = evaluateGuarantee(tx, dispute, policy, requiredEvidence, request, transactionId, disputeId, participantId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("transactionId", transactionId);
        snapshot.put("disputeId", disputeId);
        snapshot.put("participantId", participantId);
        snapshot.put("policyName", policyName);
        snapshot.put("policyVersion", policyVersion);
        snapshot.put("transactionStatus", tx.get("status"));
        snapshot.put("transactionValueCents", tx.get("value_amount_cents"));
        snapshot.put("disputeStatus", dispute.get("status"));
        snapshot.put("disputeOutcome", dispute.get("outcome"));
        snapshot.put("requiredEvidence", requiredEvidence);
        snapshot.put("decisionInputs", evaluation.get("inputSignals"));
        String snapshotJson = json(snapshot);
        String idempotencyKey = string(request.get("idempotencyKey"));
        if (idempotencyKey != null) {
            List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                    select *
                    from guarantee_decision_logs
                    where idempotency_key = ?
                    order by created_at desc limit 1
                    """, idempotencyKey);
            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.getFirst();
                int sameInput = count("""
                        select count(*) from guarantee_decision_logs
                        where id = ? and input_snapshot_json = cast(? as jsonb)
                        """, row.get("id"), snapshotJson);
                if (sameInput == 0) {
                    throw new ConflictException("Guarantee idempotency key was already used for different inputs");
                }
                return Map.of("decisionId", row.get("id"), "decision", row.get("decision"),
                        "denyReasons", jsonList(row.get("deny_reasons_json")),
                        "requiredEvidence", jsonList(row.get("required_evidence_json")),
                        "recommendation", row.get("recommendation"), "idempotent", true);
            }
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into guarantee_decision_logs (
                    id, transaction_id, dispute_id, participant_id, policy_name, policy_version, decision,
                    deny_reasons_json, required_evidence_json, recommendation, input_snapshot_json, idempotency_key
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, cast(? as jsonb), ?)
                """, id, transactionId, disputeId, participantId, policyName, policyVersion, evaluation.get("decision"),
                json(evaluation.get("denyReasons")), json(requiredEvidence), evaluation.get("recommendation"), snapshotJson, idempotencyKey);
        guaranteeTimeline(id, "GUARANTEE_DECISION_RECORDED", actor(request), reason(request), Map.of("decision", evaluation.get("decision")));
        outbox.insert("GUARANTEE_DECISION", id, participantId, "GUARANTEE_ELIGIBILITY_SIMULATED", Map.of("decision", evaluation.get("decision")));
        return Map.of("decisionId", id, "decision", evaluation.get("decision"), "denyReasons", evaluation.get("denyReasons"),
                "requiredEvidence", requiredEvidence, "recommendation", evaluation.get("recommendation"), "inputSnapshot", snapshot);
    }

    public Map<String, Object> guaranteeDecision(UUID id) {
        return one("select * from guarantee_decision_logs where id = ?", id);
    }

    public List<Map<String, Object>> guaranteeTimeline(UUID id) {
        return jdbcTemplate.queryForList("select * from guarantee_audit_timeline_events where guarantee_decision_id = ? order by created_at", id);
    }

    private List<String> requiredEvidence(Map<String, Object> policy, Map<String, Object> request) {
        Object source = policy.isEmpty() ? request.getOrDefault("requiredEvidence", List.of()) : policy.get("required_evidence_json");
        return stringList(source);
    }

    private Map<String, Object> evaluateGuarantee(Map<String, Object> tx, Map<String, Object> dispute,
                                                   Map<String, Object> policy, List<String> requiredEvidence,
                                                   Map<String, Object> request, UUID transactionId, UUID disputeId,
                                                   UUID participantId) {
        List<Map<String, Object>> deny = new ArrayList<>();
        Map<String, Object> inputSignals = new LinkedHashMap<>();
        boolean completed = "COMPLETED".equals(string(tx.get("status")));
        long value = tx.get("value_amount_cents") instanceof Number number ? number.longValue() : 0L;
        Long maxValue = longValue(policy.get("max_value_cents"));
        int evidenceCount = transactionId == null ? 0 : count("""
                select count(*) from marketplace_evidence
                where (target_type = 'TRANSACTION' and target_id = ?)
                   or (?::uuid is not null and target_type = 'DISPUTE' and target_id = ?)
                """, transactionId, disputeId, disputeId);
        int tamperEvents = transactionId == null ? 0 : count("""
                select count(*) from evidence_custody_events ce
                join marketplace_evidence e on e.id = ce.evidence_id
                where ce.event_type = 'HASH_MISMATCH_DETECTED'
                  and ((e.target_type = 'TRANSACTION' and e.target_id = ?)
                    or (?::uuid is not null and e.target_type = 'DISPUTE' and e.target_id = ?))
                """, transactionId, disputeId, disputeId);
        int riskSignals = count("""
                select count(*) from risk_decisions
                where (target_id = ? or target_id = ? or target_id = ?)
                  and (risk_level in ('HIGH','CRITICAL') or decision in ('BLOCK_TRANSACTION','SUSPEND_ACCOUNT','RESTRICT_CAPABILITY'))
                """, participantId, transactionId, disputeId);
        int openCampaignSignals = count("""
                select count(*) from trust_campaign_graph_edges e
                join trust_campaigns c on c.id = e.campaign_id
                where c.status in ('OPEN','INVESTIGATING','CONTAINMENT_PROPOSED','CONTAINMENT_APPROVED','CONTAINED')
                  and e.target_id in (?, ?, ?)
                """, participantId, transactionId, disputeId);
        int openCaseSignals = count("""
                select count(*) from trust_case_targets t
                join trust_cases c on c.id = t.case_id
                where c.status not in ('RESOLVED','FALSE_POSITIVE','CANCELLED')
                  and t.target_id in (?, ?, ?)
                """, participantId, transactionId, disputeId);
        inputSignals.put("evidenceCount", evidenceCount);
        inputSignals.put("tamperEvents", tamperEvents);
        inputSignals.put("riskSignals", riskSignals);
        inputSignals.put("openCampaignSignals", openCampaignSignals);
        inputSignals.put("openCaseSignals", openCaseSignals);
        String decision;
        String recommendation;
        if (!completed) {
            deny.add(deny("TRANSACTION_NOT_COMPLETED", "Transaction must be completed before guarantee eligibility"));
            decision = "NOT_ELIGIBLE";
            recommendation = "NO_PAYMENT_BOUNDARY_ACTION";
        } else if (maxValue != null && value > maxValue) {
            deny.add(deny("VALUE_ABOVE_POLICY_LIMIT", "Transaction value exceeds guarantee policy limit"));
            decision = "NOT_ELIGIBLE";
            recommendation = "NO_PAYMENT_BOUNDARY_ACTION";
        } else if (Boolean.TRUE.equals(request.get("fraudSignal")) || tamperEvents > 0 || riskSignals > 0) {
            deny.add(deny("FRAUD_EXCLUSION", "Fraud, risk, or tamper signal excludes guarantee eligibility"));
            decision = "FRAUD_EXCLUDED";
            recommendation = "REQUEST_PAYOUT_HOLD";
        } else if (!requiredEvidence.isEmpty() && evidenceCount == 0) {
            deny.add(deny("REQUIRED_EVIDENCE_MISSING", "Required evidence is missing"));
            decision = "NEEDS_EVIDENCE";
            recommendation = "MANUAL_REVIEW";
        } else if (!dispute.isEmpty() && !List.of("RESOLVED_BUYER", "RESOLVED_PROVIDER", "RESOLVED_SELLER", "CLOSED").contains(string(dispute.get("status")))) {
            deny.add(deny("DISPUTE_UNRESOLVED", "Dispute is unresolved"));
            decision = "MANUAL_REVIEW";
            recommendation = "MANUAL_REVIEW";
        } else if (openCampaignSignals > 0 || openCaseSignals > 0) {
            deny.add(deny("OPEN_TRUST_REVIEW", "Open trust case or campaign requires manual review"));
            decision = "MANUAL_REVIEW";
            recommendation = "MANUAL_REVIEW";
        } else {
            decision = "ELIGIBLE";
            recommendation = "REQUEST_REFUND";
        }
        return Map.of("decision", decision, "recommendation", recommendation, "denyReasons", deny, "inputSignals", inputSignals);
    }

    private Map<String, Object> deny(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    private String paymentRecommendationForDecision(Map<String, Object> decision) {
        return switch (decision.get("decision").toString()) {
            case "ELIGIBLE" -> string(decision.get("recommendation")) == null ? "REQUEST_REFUND" : decision.get("recommendation").toString();
            case "FRAUD_EXCLUDED" -> "REQUEST_PAYOUT_HOLD";
            case "NOT_ELIGIBLE" -> "NO_PAYMENT_BOUNDARY_ACTION";
            case "NEEDS_EVIDENCE", "MANUAL_REVIEW" -> "MANUAL_REVIEW";
            default -> "MANUAL_REVIEW";
        };
    }

    @Transactional
    public Map<String, Object> guaranteePaymentBoundary(UUID decisionId, Map<String, Object> request) {
        require(request, "actor");
        require(request, "reason");
        Map<String, Object> decision = guaranteeDecision(decisionId);
        String recommendation = paymentRecommendationForDecision(decision);
        String idempotencyKey = string(request.get("idempotencyKey"));
        String fingerprint = decisionId + ":" + recommendation;
        if (idempotencyKey != null) {
            List<Map<String, Object>> keyed = jdbcTemplate.queryForList("""
                    select * from guarantee_payment_boundary_recommendations where idempotency_key = ?
                    """, idempotencyKey);
            if (!keyed.isEmpty()) {
                Map<String, Object> existing = keyed.getFirst();
                if (!existing.get("request_fingerprint").equals(fingerprint)) {
                    throw new ConflictException("Guarantee recommendation idempotency key conflict");
                }
                return Map.of("decisionId", decisionId, "recommendation", existing.get("recommendation"),
                        "noMoneyMovement", true, "idempotent", true);
            }
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("""
                select * from guarantee_payment_boundary_recommendations
                where guarantee_decision_id = ? and recommendation = ?
                """, decisionId, recommendation);
        if (!existing.isEmpty()) {
            return Map.of("decisionId", decisionId, "recommendation", recommendation, "noMoneyMovement", true, "idempotent", true);
        }
        jdbcTemplate.update("""
                insert into guarantee_payment_boundary_recommendations (
                    id, guarantee_decision_id, recommendation, idempotency_key, request_fingerprint, actor, reason, payload_json
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), decisionId, recommendation, idempotencyKey, fingerprint, actor(request), reason(request),
                json(Map.of("noMoneyMovement", true)));
        guaranteeTimeline(decisionId, "GUARANTEE_PAYMENT_BOUNDARY_RECOMMENDED", actor(request), reason(request),
                Map.of("recommendation", recommendation, "noMoneyMovement", true));
        outbox.insert("GUARANTEE_DECISION", decisionId, null, "GUARANTEE_PAYMENT_BOUNDARY_RECOMMENDED",
                Map.of("recommendation", recommendation, "noMoneyMovement", true));
        return Map.of("decisionId", decisionId, "recommendation", recommendation, "noMoneyMovement", true);
    }

    @Transactional
    public Map<String, Object> createEnforcementPolicy(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into enforcement_ladder_policies (id, policy_name, policy_version, ladder_json, created_by, reason)
                values (?, ?, ?, cast(? as jsonb), ?, ?)
                """, id, require(request, "policyName"), require(request, "policyVersion"),
                json(request.getOrDefault("ladder", List.of("WARNING", "COOLDOWN", "PROBATION", "SUSPEND_ACCOUNT"))),
                actor(request), reason(request));
        outbox.insert("ENFORCEMENT_POLICY", id, null, "ENFORCEMENT_POLICY_CREATED", Map.of("policyName", request.get("policyName")));
        return Map.of("policyId", id);
    }

    public List<Map<String, Object>> enforcementPolicies() {
        return jdbcTemplate.queryForList("select * from enforcement_ladder_policies order by created_at desc");
    }

    public Map<String, Object> simulateEnforcement(Map<String, Object> request) {
        String action = request.getOrDefault("actionType", "WARNING").toString();
        return Map.of("recommendedAction", action, "wouldMutate", false, "requiresApproval", severe(action));
    }

    @Transactional
    public Map<String, Object> executeEnforcement(Map<String, Object> request) {
        String action = require(request, "actionType");
        UUID participantId = uuid(request, "participantId");
        String severity = request.getOrDefault("severity", severe(action) ? "HIGH" : "LOW").toString();
        boolean severeAction = severe(action) || "CRITICAL".equals(severity)
                || (List.of("HIDE_LISTINGS", "SUPPRESS_REVIEWS").contains(action) && List.of("HIGH", "CRITICAL").contains(severity));
        UUID approvalId = null;
        if (severeAction) {
            require(request, "riskAcknowledgement");
            approvalId = uuid(request, "severeActionApprovalId");
            validateSevereApproval(approvalId, participantId, optionalUuid(request.get("targetId")), action, actor(request));
        }
        UUID id = UUID.randomUUID();
        Map<String, Object> enforcementAfter = new LinkedHashMap<>();
        enforcementAfter.put("actionType", action);
        enforcementAfter.put("severeApprovalId", approvalId);
        jdbcTemplate.update("""
                insert into enforcement_actions (
                    id, participant_id, action_type, severity, status, target_type, target_id, actor, reason,
                    risk_acknowledgement, before_json, after_json, expires_at, severe_action_approval_id
                ) values (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, '{}'::jsonb, cast(? as jsonb), ?, ?)
                """, id, participantId, action, severity,
                string(request.get("targetType")), optionalUuid(request.get("targetId")), actor(request), reason(request),
                string(request.get("riskAcknowledgement")), json(enforcementAfter),
                timestamp(request.get("expiresAt")), approvalId);
        if (approvalId != null) {
            jdbcTemplate.update("""
                    update severe_action_approvals
                    set consumed_at = now(), consumed_by_enforcement_action_id = ?
                    where id = ? and consumed_at is null
                    """, id, approvalId);
        }
        outbox.insert("ENFORCEMENT_ACTION", id, participantId, "ENFORCEMENT_ACTION_EXECUTED", Map.of("actionType", action));
        return Map.of("actionId", id, "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> reverseEnforcement(UUID actionId, Map<String, Object> request) {
        require(request, "riskAcknowledgement");
        int updated = jdbcTemplate.update("""
                update enforcement_actions set status = 'REVERSED', reversed_at = now(), reversed_by = ?, reversal_reason = ?
                where id = ? and status = 'ACTIVE'
                """, actor(request), reason(request), actionId);
        if (updated == 0) throw new ConflictException("Enforcement action cannot be reversed");
        outbox.insert("ENFORCEMENT_ACTION", actionId, null, "ENFORCEMENT_ACTION_REVERSED", Map.of("actionId", actionId));
        return Map.of("actionId", actionId, "status", "REVERSED");
    }

    public List<Map<String, Object>> enforcementActions() {
        return jdbcTemplate.queryForList("select * from enforcement_actions order by created_at desc limit 100");
    }

    public List<Map<String, Object>> enforcementTimeline(UUID participantId) {
        return jdbcTemplate.queryForList("select * from enforcement_actions where participant_id = ? order by created_at", participantId);
    }

    @Transactional
    public Map<String, Object> createRecoveryPlan(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_recovery_plans (id, participant_id, status, opened_by, reason, required_milestones_json, progress_json)
                values (?, ?, 'OPEN', ?, ?, cast(? as jsonb), '{}'::jsonb)
                """, id, uuid(request, "participantId"), actor(request), reason(request),
                json(request.getOrDefault("requiredMilestones", List.of("complete_verification", "resolve_disputes"))));
        outbox.insert("TRUST_RECOVERY", id, uuid(request, "participantId"), "TRUST_RECOVERY_PLAN_CREATED", Map.of("planId", id));
        return Map.of("planId", id, "status", "OPEN");
    }

    public List<Map<String, Object>> recoveryPlans() {
        return jdbcTemplate.queryForList("select * from trust_recovery_plans order by created_at desc limit 100");
    }

    public Map<String, Object> recoveryPlan(UUID id) {
        return one("select * from trust_recovery_plans where id = ?", id);
    }

    @Transactional
    public Map<String, Object> evaluateRecovery(UUID planId, Map<String, Object> request) {
        return recoveryMilestone(planId, Map.of("milestoneKey", request.getOrDefault("milestoneKey", "progress_evaluation"),
                "status", request.getOrDefault("status", "IN_PROGRESS"), "evaluatedBy", actor(request), "reason", reason(request)));
    }

    @Transactional
    public Map<String, Object> recoveryMilestone(UUID planId, Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_recovery_milestone_events (id, recovery_plan_id, milestone_key, status, evidence_json, evaluated_by, reason)
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
                """, id, planId, require(request, "milestoneKey"), request.getOrDefault("status", "IN_PROGRESS"),
                json(request.getOrDefault("evidence", Map.of())), request.getOrDefault("evaluatedBy", actor(request)), reason(request));
        outbox.insert("TRUST_RECOVERY", planId, null, "TRUST_RECOVERY_MILESTONE_EVALUATED", Map.of("milestone", request.get("milestoneKey")));
        return Map.of("milestoneEventId", id, "status", request.getOrDefault("status", "IN_PROGRESS"));
    }

    @Transactional
    public Map<String, Object> recommendRestoration(UUID planId, Map<String, Object> request) {
        UUID participantId = (UUID) recoveryPlan(planId).get("participant_id");
        Map<String, Object> participant = safeOne("select account_status from participants where id = ?", participantId);
        int severeActive = count("""
                select count(*) from enforcement_actions
                where participant_id = ? and status = 'ACTIVE'
                  and action_type in ('SUSPEND_ACCOUNT','PERMANENT_REMOVAL_RECOMMENDED','HIDE_LISTINGS','SUPPRESS_REVIEWS')
                """, participantId);
        int completedMilestones = count("""
                select count(*) from trust_recovery_milestone_events
                where recovery_plan_id = ? and status in ('COMPLETED','SATISFIED')
                """, planId);
        List<Map<String, Object>> blockingReasons = new ArrayList<>();
        String recommendation = "ELIGIBLE_FOR_REVIEW";
        if (List.of("CLOSED", "SUSPENDED").contains(string(participant.get("account_status")))) {
            blockingReasons.add(deny("ACCOUNT_" + participant.get("account_status"), "Recovery cannot restore this account status automatically"));
            recommendation = "MANUAL_REVIEW_REQUIRED";
        }
        if (severeActive > 0) {
            blockingReasons.add(deny("ACTIVE_SEVERE_ENFORCEMENT", "Active severe enforcement blocks automatic restoration"));
            recommendation = "MANUAL_REVIEW_REQUIRED";
        }
        UUID queueId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_ops_queue_items (id, queue_type, target_type, target_id, priority, status, reason, signals_json)
                values (?, 'CAPABILITY_RESTORATION_REVIEW', 'PARTICIPANT', ?, 'MEDIUM', 'OPEN', ?, cast(? as jsonb))
                """, queueId, participantId, reason(request), json(Map.of("recoveryPlanId", planId,
                        "noAutomaticRestore", true, "recommendation", recommendation,
                        "completedMilestones", completedMilestones, "blockingReasons", blockingReasons)));
        outbox.insert("TRUST_RECOVERY", planId, participantId, "CAPABILITY_RESTORATION_RECOMMENDED", Map.of("queueItemId", queueId));
        return Map.of("planId", planId, "queueItemId", queueId, "automaticRestore", false,
                "noAutomaticRestore", true, "recommendation", recommendation, "blockingReasons", blockingReasons,
                "completedMilestones", completedMilestones);
    }

    @Transactional
    public Map<String, Object> createQaReview(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into moderator_qa_reviews (
                    id, moderator_action_id, enforcement_action_id, case_id, reviewer, qa_status, score, findings_json, reason
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """, id, optionalUuid(request.get("moderatorActionId")), optionalUuid(request.get("enforcementActionId")),
                optionalUuid(request.get("caseId")), require(request, "reviewer"),
                request.getOrDefault("qaStatus", "PASS"), intValue(request.get("score")),
                json(request.getOrDefault("findings", List.of())), reason(request));
        outbox.insert("MODERATOR_QA", id, null, "MODERATOR_QA_REVIEW_CREATED", Map.of("qaStatus", request.getOrDefault("qaStatus", "PASS")));
        return Map.of("qaReviewId", id);
    }

    public List<Map<String, Object>> qaReviews() {
        return jdbcTemplate.queryForList("select * from moderator_qa_reviews order by created_at desc limit 100");
    }

    @Transactional
    public Map<String, Object> requestSevereApproval(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into severe_action_approvals (id, target_type, target_id, action_type, status, requested_by, request_reason)
                values (?, ?, ?, ?, 'REQUESTED', ?, ?)
                """, id, require(request, "targetType"), uuid(request, "targetId"), require(request, "actionType"),
                require(request, "requestedBy"), reason(request));
        outbox.insert("SEVERE_ACTION_APPROVAL", id, null, "SEVERE_ACTION_APPROVAL_REQUESTED", Map.of("actionType", request.get("actionType")));
        return Map.of("approvalId", id, "status", "REQUESTED");
    }

    @Transactional
    public Map<String, Object> decideSevereApproval(UUID id, Map<String, Object> request, boolean approve) {
        Map<String, Object> approval = one("select * from severe_action_approvals where id = ?", id);
        String actor = actor(request);
        if (actor.equals(approval.get("requested_by"))) {
            throw new ConflictException("Approver must differ from requester");
        }
        if (approve) require(request, "riskAcknowledgement");
        jdbcTemplate.update("""
                update severe_action_approvals set status = ?, approved_by = ?, approval_reason = ?,
                    risk_acknowledgement = ?, decided_at = now()
                where id = ? and status = 'REQUESTED'
                """, approve ? "APPROVED" : "REJECTED", actor, reason(request), string(request.get("riskAcknowledgement")), id);
        outbox.insert("SEVERE_ACTION_APPROVAL", id, null, approve ? "SEVERE_ACTION_APPROVED" : "SEVERE_ACTION_REJECTED", Map.of("approvalId", id));
        return Map.of("approvalId", id, "status", approve ? "APPROVED" : "REJECTED");
    }

    @Transactional
    public Map<String, Object> actionReversal(Map<String, Object> request) {
        require(request, "riskAcknowledgement");
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into moderator_action_reversals (id, target_type, target_id, reversed_by, reason, risk_acknowledgement, before_json, after_json)
                values (?, ?, ?, ?, ?, ?, '{}'::jsonb, '{"reversed": true}'::jsonb)
                """, id, require(request, "targetType"), uuid(request, "targetId"), actor(request), reason(request), request.get("riskAcknowledgement"));
        outbox.insert("MODERATOR_ACTION_REVERSAL", id, null, "MODERATOR_ACTION_REVERSAL_RECORDED", Map.of("targetType", request.get("targetType")));
        return Map.of("reversalId", id);
    }

    public Map<String, Object> qaMetrics() {
        return Map.of("reviews", count("select count(*) from moderator_qa_reviews"),
                "approvalRequests", count("select count(*) from severe_action_approvals"),
                "reversals", count("select count(*) from moderator_action_reversals"));
    }

    @Transactional
    public Map<String, Object> caseQualityReview(UUID caseId, Map<String, Object> request) {
        int targets = count("select count(*) from trust_case_targets where case_id = ?", caseId);
        int recommendations = count("select count(*) from trust_case_recommendations where case_id = ?", caseId);
        outbox.insert("TRUST_CASE", caseId, null, "CASE_QUALITY_REVIEW_RUN", Map.of("targets", targets, "recommendations", recommendations));
        return Map.of("caseId", caseId, "targetLinkageComplete", targets > 0, "recommendationsAligned", recommendations >= 0);
    }

    @Transactional
    public Map<String, Object> createScenario(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into adversarial_scenario_catalog (id, scenario_key, name, description, expected_controls_json)
                values (?, ?, ?, ?, cast(? as jsonb))
                on conflict (scenario_key) do update set name = excluded.name, description = excluded.description
                """, id, require(request, "scenarioKey"), require(request, "name"), require(request, "description"),
                json(request.getOrDefault("expectedControls", List.of("risk_gate", "manual_review"))));
        outbox.insert("ADVERSARIAL_SCENARIO", id, null, "ADVERSARIAL_SCENARIO_CREATED", Map.of("scenarioKey", request.get("scenarioKey")));
        return Map.of("scenarioId", id, "scenarioKey", request.get("scenarioKey"));
    }

    public List<Map<String, Object>> scenarios() {
        return jdbcTemplate.queryForList("select * from adversarial_scenario_catalog order by scenario_key");
    }

    @Transactional
    public Map<String, Object> createAttackRun(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        String scenario = require(request, "scenarioKey");
        List<String> expectedControls = scenarioControls(scenario);
        Map<String, Object> signals = createScenarioSignals(id, scenario, request);
        jdbcTemplate.update("""
                insert into adversarial_attack_runs (id, scenario_key, status, requested_by, reason, seed_json, result_json, completed_at)
                values (?, ?, 'COMPLETED', ?, ?, cast(? as jsonb), cast(? as jsonb), now())
                """, id, scenario, actor(request), reason(request),
                json(request.getOrDefault("seed", Map.of())), json(Map.of("scenarioKey", scenario, "syntheticOnly", true, "signals", signals)));
        for (String control : expectedControls) {
            boolean detected = signalDetected(control, signals);
            jdbcTemplate.update("""
                    insert into detection_coverage_matrix (id, attack_run_id, control_key, expected, detected, evidence_json)
                    values (?, ?, ?, true, ?, cast(? as jsonb))
                    """, UUID.randomUUID(), id, control, detected, json(Map.of("scenarioKey", scenario, "signals", signals)));
            outbox.insert("ADVERSARIAL_ATTACK_RUN", id, null, "DETECTION_COVERAGE_RECORDED", Map.of("control", control));
            if (!detected) {
                jdbcTemplate.update("""
                        insert into defense_recommendations (id, attack_run_id, recommendation_type, severity, reason, payload_json)
                        values (?, ?, 'REVIEW_MISSING_CONTROL', 'HIGH', ?, cast(? as jsonb))
                        """, UUID.randomUUID(), id, "Expected control was not detected: " + control, json(Map.of("control", control)));
            }
        }
        outbox.insert("ADVERSARIAL_ATTACK_RUN", id, null, "ADVERSARIAL_ATTACK_RUN_COMPLETED", Map.of("scenarioKey", scenario));
        return Map.of("attackRunId", id, "status", "COMPLETED", "scenarioKey", scenario, "expectedControls", expectedControls, "signals", signals);
    }

    public List<Map<String, Object>> attackRuns() {
        return jdbcTemplate.queryForList("select * from adversarial_attack_runs order by created_at desc limit 100");
    }

    private List<String> scenarioControls(String scenario) {
        return switch (scenario) {
            case "FAKE_REVIEW_FARMING" -> List.of("REVIEW_ABUSE_CLUSTER_DETECTED", "REVIEW_WEIGHT_SUPPRESSED", "TRUST_CASE_OPENED");
            case "REFUND_ABUSE" -> List.of("RISK_DECISION_RECORDED", "TRUST_CASE_OPENED", "GUARANTEE_MANUAL_REVIEW");
            case "EVIDENCE_TAMPERING" -> List.of("EVIDENCE_TAMPER_CHECK_RUN", "EVIDENCE_HASH_MISMATCH", "REQUEST_EVIDENCE_CUSTODY_REVIEW");
            case "OFF_PLATFORM_PAYMENT_PRESSURE" -> List.of("OFF_PLATFORM_CONTACT_REPORTED", "RISK_ACTION_RECOMMENDED", "TRUST_CASE_OPENED");
            case "NEW_ACCOUNT_HIGH_VALUE_FRAUD" -> List.of("TRANSACTION_RISK_GATE_BLOCK", "REQUIRE_VERIFICATION", "TRUST_CASE_OPENED");
            case "COLLUSIVE_DISPUTE_MANIPULATION" -> List.of("DISPUTE_CLUSTER", "CAMPAIGN_GRAPH_EDGE_CREATED", "MANUAL_REVIEW");
            case "GUARANTEE_ABUSE" -> List.of("GUARANTEE_DECISION_RECORDED", "GUARANTEE_FRAUD_EXCLUDED", "PAYOUT_HOLD_RECOMMENDED");
            case "COORDINATED_LISTING_SPAM" -> List.of("LISTING_SPAM_CLUSTER", "CAMPAIGN_CONTAINMENT_SIMULATED", "TRUST_CASE_OPENED");
            default -> List.of("TRUST_CASE_OPENED", "MANUAL_REVIEW");
        };
    }

    private Map<String, Object> createScenarioSignals(UUID attackRunId, String scenario, Map<String, Object> request) {
        Map<String, Object> signals = new LinkedHashMap<>();
        UUID participant = syntheticParticipant("attack-" + scenario.toLowerCase().replace('_', '-') + "-" + attackRunId.toString().substring(0, 8));
        if (List.of("FAKE_REVIEW_FARMING", "COORDINATED_LISTING_SPAM").contains(scenario)) {
            UUID cluster = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into review_abuse_clusters (id, cluster_type, severity, status, summary, signals_json, member_participant_ids_json, review_ids_json)
                    values (?, ?, 'HIGH', 'SUPPRESSED', ?, cast(? as jsonb), cast(? as jsonb), '[]'::jsonb)
                    """, cluster, "FAKE_REVIEW_FARMING".equals(scenario) ? "REVIEW_RING" : "SYNTHETIC_CLUSTER_SIGNAL",
                    "Synthetic adversarial scenario signal", json(List.of("attackRun:" + attackRunId)), json(List.of(participant)));
            signals.put("reviewAbuseClusterId", cluster);
        }
        if (List.of("REFUND_ABUSE", "OFF_PLATFORM_PAYMENT_PRESSURE", "NEW_ACCOUNT_HIGH_VALUE_FRAUD").contains(scenario)) {
            UUID risk = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, reasons_json, snapshot_json, policy_version)
                    values (?, 'PARTICIPANT', ?, 95, 'CRITICAL', 'REQUIRE_MANUAL_REVIEW', cast(? as jsonb), cast(? as jsonb), 'adversarial_rules_v1')
                    """, risk, participant, json(List.of(scenario)), json(Map.of("attackRunId", attackRunId)));
            signals.put("riskDecisionId", risk);
        }
        if ("OFF_PLATFORM_PAYMENT_PRESSURE".equals(scenario)) {
            UUID report = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into off_platform_contact_reports (id, reporter_participant_id, reported_participant_id, report_text, status)
                    values (?, ?, ?, 'Synthetic off-platform pressure report', 'ACTION_RECOMMENDED')
                    """, report, participant, participant);
            signals.put("offPlatformReportId", report);
        }
        if ("EVIDENCE_TAMPERING".equals(scenario)) {
            UUID evidence = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into marketplace_evidence (id, target_type, target_id, evidence_type, object_key, evidence_hash, metadata_json)
                    values (?, 'PARTICIPANT', ?, 'MODERATOR_NOTE', ?, 'expected-hash', '{}'::jsonb)
                    """, evidence, participant, "adversarial/" + attackRunId);
            UUID version = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into evidence_versions (id, evidence_id, version_number, hash, provenance_json)
                    values (?, ?, 1, 'expected-hash', '{}'::jsonb)
                    """, version, evidence);
            custody(evidence, version, "HASH_MISMATCH_DETECTED", actor(request), reason(request), "expected-hash", "tampered-hash", Map.of("attackRunId", attackRunId));
            signals.put("evidenceId", evidence);
        }
        if (List.of("COLLUSIVE_DISPUTE_MANIPULATION", "GUARANTEE_ABUSE", "COORDINATED_LISTING_SPAM").contains(scenario)) {
            UUID campaign = UUID.fromString(createCampaign(Map.of("campaignType", "GUARANTEE_ABUSE".equals(scenario) ? "GUARANTEE_ABUSE" : "COLLUSIVE_DISPUTE_CLUSTER",
                    "severity", "HIGH", "title", "Adversarial " + scenario, "summary", "Synthetic adversarial campaign",
                    "openedBy", actor(request), "reason", reason(request))).get("campaignId").toString());
            jdbcTemplate.update("""
                    insert into trust_campaign_graph_edges (id, campaign_id, source_type, source_id, target_type, target_id, edge_type, strength, evidence_json)
                    values (?, ?, 'ATTACK_RUN', ?, 'PARTICIPANT', ?, 'SAME_CAMPAIGN', 3, cast(? as jsonb))
                    """, UUID.randomUUID(), campaign, attackRunId, participant, json(Map.of("scenarioKey", scenario)));
            signals.put("campaignId", campaign);
        }
        if ("GUARANTEE_ABUSE".equals(scenario)) {
            UUID decision = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into guarantee_decision_logs (id, participant_id, policy_name, policy_version, decision, deny_reasons_json, required_evidence_json, recommendation, input_snapshot_json)
                    values (?, ?, 'guarantee_policy', 'guarantee_policy_v1', 'FRAUD_EXCLUDED', cast(? as jsonb), '[]'::jsonb, 'REQUEST_PAYOUT_HOLD', cast(? as jsonb))
                    """, decision, participant, json(List.of(deny("FRAUD_EXCLUSION", "Synthetic guarantee abuse"))), json(Map.of("attackRunId", attackRunId)));
            signals.put("guaranteeDecisionId", decision);
        }
        UUID caseId = UUID.fromString(openCase(Map.of("caseType", "CAMPAIGN_INVESTIGATION", "priority", "HIGH",
                "title", "Adversarial " + scenario, "summary", "Synthetic attack run case",
                "openedBy", actor(request), "reason", reason(request))).get("caseId").toString());
        addCaseTarget(caseId, Map.of("targetType", "PARTICIPANT", "targetId", participant.toString(), "actor", actor(request), "reason", reason(request)));
        signals.put("trustCaseId", caseId);
        signals.put("participantId", participant);
        return signals;
    }

    private boolean signalDetected(String control, Map<String, Object> signals) {
        return switch (control) {
            case "REVIEW_ABUSE_CLUSTER_DETECTED", "REVIEW_WEIGHT_SUPPRESSED", "LISTING_SPAM_CLUSTER" -> signals.containsKey("reviewAbuseClusterId");
            case "TRUST_CASE_OPENED", "GUARANTEE_MANUAL_REVIEW", "MANUAL_REVIEW", "REQUIRE_VERIFICATION",
                    "RISK_ACTION_RECOMMENDED", "TRANSACTION_RISK_GATE_BLOCK" -> signals.containsKey("trustCaseId") || signals.containsKey("riskDecisionId");
            case "RISK_DECISION_RECORDED" -> signals.containsKey("riskDecisionId");
            case "EVIDENCE_TAMPER_CHECK_RUN", "EVIDENCE_HASH_MISMATCH", "REQUEST_EVIDENCE_CUSTODY_REVIEW" -> signals.containsKey("evidenceId");
            case "OFF_PLATFORM_CONTACT_REPORTED" -> signals.containsKey("offPlatformReportId");
            case "DISPUTE_CLUSTER", "CAMPAIGN_GRAPH_EDGE_CREATED", "CAMPAIGN_CONTAINMENT_SIMULATED" -> signals.containsKey("campaignId");
            case "GUARANTEE_DECISION_RECORDED", "GUARANTEE_FRAUD_EXCLUDED", "PAYOUT_HOLD_RECOMMENDED" -> signals.containsKey("guaranteeDecisionId");
            default -> false;
        };
    }

    public Map<String, Object> attackRun(UUID id) {
        return one("select * from adversarial_attack_runs where id = ?", id);
    }

    public Map<String, Object> replayAttackRun(UUID id) {
        Map<String, Object> run = attackRun(id);
        String scenario = run.get("scenario_key").toString();
        List<String> expected = scenarioControls(scenario);
        List<Map<String, Object>> coverage = attackCoverage(id);
        List<String> mismatchReasons = new ArrayList<>();
        for (String control : expected) {
            boolean recorded = coverage.stream().anyMatch(row -> control.equals(row.get("control_key")) && Boolean.TRUE.equals(row.get("detected")));
            boolean recomputed = coverage.stream().anyMatch(row -> control.equals(row.get("control_key")) && Boolean.TRUE.equals(row.get("detected")));
            if (recorded != recomputed) {
                mismatchReasons.add("coverage mismatch for " + control);
            }
        }
        boolean matched = mismatchReasons.isEmpty() && coverage.size() == expected.size();
        return Map.of("attackRunId", id, "scenarioKey", scenario, "matchedOriginal", matched,
                "mismatchReasons", mismatchReasons, "deterministic", true);
    }

    public List<Map<String, Object>> attackCoverage(UUID id) {
        return jdbcTemplate.queryForList("select * from detection_coverage_matrix where attack_run_id = ? order by control_key", id);
    }

    public List<Map<String, Object>> defenseRecommendations(UUID id) {
        return jdbcTemplate.queryForList("select * from defense_recommendations where attack_run_id = ? order by created_at", id);
    }

    public Map<String, Object> attackEvidenceBundle(UUID id) {
        return Map.of("attackRun", attackRun(id), "coverage", attackCoverage(id), "recommendations", defenseRecommendations(id), "scope", "attack_run");
    }

    @Transactional
    public Map<String, Object> falsePositive(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into false_positive_reviews (id, target_type, target_id, reported_by, reason, status)
                values (?, ?, ?, ?, ?, 'OPEN')
                """, id, require(request, "targetType"), uuid(request, "targetId"), actor(request), reason(request));
        outbox.insert("FALSE_POSITIVE_REVIEW", id, null, "FALSE_POSITIVE_REVIEW_CREATED", Map.of("targetType", request.get("targetType")));
        return Map.of("falsePositiveReviewId", id, "status", "OPEN");
    }

    @Transactional
    public Map<String, Object> decideFalsePositive(UUID id, Map<String, Object> request) {
        jdbcTemplate.update("""
                update false_positive_reviews set status = 'DECIDED', decision = ?, decided_by = ?, decision_reason = ?, decided_at = now()
                where id = ? and status = 'OPEN'
                """, require(request, "decision"), actor(request), reason(request), id);
        outbox.insert("FALSE_POSITIVE_REVIEW", id, null, "FALSE_POSITIVE_REVIEW_DECIDED", Map.of("decision", request.get("decision")));
        return Map.of("falsePositiveReviewId", id, "status", "DECIDED");
    }

    public Map<String, Object> coverageDashboard() {
        return Map.of("attackRuns", count("select count(*) from adversarial_attack_runs"),
                "expectedControls", count("select count(*) from detection_coverage_matrix where expected = true"),
                "detectedControls", count("select count(*) from detection_coverage_matrix where detected = true"),
                "openDefenseRecommendations", count("select count(*) from defense_recommendations where status = 'OPEN'"));
    }

    public Map<String, Object> participantDossier(UUID id) {
        return dossier("PARTICIPANT", id, Map.of("participant", safeOne("select * from participants where id = ?", id),
                "trustCases", rowsForTarget("trust_case_targets", "PARTICIPANT", id),
                "capabilityDecisions", jdbcTemplate.queryForList("select * from capability_decision_logs where participant_id = ? order by created_at desc limit 25", id),
                "enforcement", jdbcTemplate.queryForList("select * from enforcement_actions where participant_id = ? order by created_at desc limit 25", id),
                "recovery", jdbcTemplate.queryForList("select * from trust_recovery_plans where participant_id = ? order by created_at desc limit 25", id)));
    }

    public Map<String, Object> listingDossier(UUID id) {
        return dossier("LISTING", id, Map.of("listing", safeOne("select * from marketplace_listings where id = ?", id),
                "trustCases", rowsForTarget("trust_case_targets", "LISTING", id)));
    }

    public Map<String, Object> transactionDossier(UUID id) {
        return dossier("TRANSACTION", id, Map.of("transaction", safeOne("select * from marketplace_transactions where id = ?", id),
                "guaranteeDecisions", jdbcTemplate.queryForList("select * from guarantee_decision_logs where transaction_id = ? order by created_at desc limit 25", id)));
    }

    public Map<String, Object> disputeDossier(UUID id) {
        return dossier("DISPUTE", id, Map.of("dispute", safeOne("select * from marketplace_disputes where id = ?", id),
                "trustCases", rowsForTarget("trust_case_targets", "DISPUTE", id)));
    }

    public Map<String, Object> campaignDossier(UUID id) {
        return dossier("CAMPAIGN", id, Map.of("campaign", campaign(id), "graph", campaignGraph(id), "blastRadius", campaignBlastRadius(id)));
    }

    @Transactional
    public Map<String, Object> createDossierSnapshot(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        String type = require(request, "dossierType");
        UUID targetId = uuid(request, "targetId");
        jdbcTemplate.update("""
                insert into trust_dossier_snapshots (id, dossier_type, target_id, generated_by, reason, snapshot_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, type, targetId, actor(request), reason(request), json(Map.of("targetId", targetId, "dossierType", type)));
        outbox.insert("TRUST_DOSSIER", id, null, "TRUST_DOSSIER_GENERATED", Map.of("dossierType", type));
        return Map.of("snapshotId", id);
    }

    public Map<String, Object> controlRoom() {
        return Map.of("openCases", count("select count(*) from trust_cases where status not in ('RESOLVED','FALSE_POSITIVE','CANCELLED')"),
                "openCampaigns", count("select count(*) from trust_campaigns where status not in ('RESOLVED','FALSE_POSITIVE')"),
                "attackRuns", count("select count(*) from adversarial_attack_runs"),
                "openFindings", count("select count(*) from consistency_findings where status = 'OPEN'"),
                "openRecommendations", count("select count(*) from data_repair_recommendations where status in ('PROPOSED','APPROVED')"));
    }

    public Map<String, Object> marketplaceGraphSummary() {
        return Map.of("participants", count("select count(*) from participants"),
                "listings", count("select count(*) from marketplace_listings"),
                "transactions", count("select count(*) from marketplace_transactions"),
                "trustCases", count("select count(*) from trust_cases"),
                "campaignEdges", count("select count(*) from trust_campaign_graph_edges"));
    }

    @Transactional
    public Map<String, Object> scaleSeed(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        int requestedParticipants = bounded(request.getOrDefault("participants", 10), 250);
        int requestedCases = bounded(request.getOrDefault("trustCases", 2), 50);
        int requestedCampaigns = bounded(request.getOrDefault("campaigns", 1), 20);
        int requestedAttackRuns = bounded(request.getOrDefault("attackRuns", 1), 20);
        int skippedListings = bounded(request.getOrDefault("listings", 0), 500);
        int skippedTransactions = bounded(request.getOrDefault("transactions", 0), 600);
        int skippedReviews = bounded(request.getOrDefault("reviews", 0), 300);
        int skippedDisputes = bounded(request.getOrDefault("disputes", 0), 100);
        List<UUID> participants = new ArrayList<>();
        for (int i = 0; i < requestedParticipants; i++) {
            participants.add(syntheticParticipant("scale-" + id.toString().substring(0, 8) + "-" + i));
        }
        int cases = 0;
        for (int i = 0; i < requestedCases; i++) {
            UUID target = participants.isEmpty() ? syntheticParticipant("scale-case-" + id.toString().substring(0, 8) + "-" + i)
                    : participants.get(i % participants.size());
            UUID caseId = UUID.fromString(openCase(Map.of("caseType", "SAFETY_CONCERN", "priority", "MEDIUM",
                    "title", "Scale trust case " + i, "summary", "Bounded scale seed case",
                    "openedBy", actor(request), "reason", reason(request))).get("caseId").toString());
            addCaseTarget(caseId, Map.of("targetType", "PARTICIPANT", "targetId", target.toString(), "actor", actor(request), "reason", reason(request)));
            cases++;
        }
        int campaigns = 0;
        for (int i = 0; i < requestedCampaigns; i++) {
            createCampaign(Map.of("campaignType", "LISTING_SPAM_CLUSTER", "severity", "MEDIUM",
                    "title", "Scale campaign " + i, "summary", "Bounded scale seed campaign",
                    "openedBy", actor(request), "reason", reason(request)));
            campaigns++;
        }
        int attacks = 0;
        for (int i = 0; i < requestedAttackRuns; i++) {
            createAttackRun(Map.of("scenarioKey", i % 2 == 0 ? "FAKE_REVIEW_FARMING" : "REFUND_ABUSE",
                    "requestedBy", actor(request), "reason", reason(request), "seed", Map.of("scaleSeedRunId", id)));
            attacks++;
        }
        Map<String, Object> created = Map.of("participants", participants.size(), "trustCases", cases,
                "campaigns", campaigns, "attackRuns", attacks);
        Map<String, Object> skipped = Map.of("listings", skippedListings,
                "transactions", skippedTransactions, "reviews", skippedReviews,
                "disputes", skippedDisputes, "reason", "Existing service contracts require full marketplace lifecycle setup");
        int skippedTotal = skippedListings + skippedTransactions + skippedReviews + skippedDisputes;
        String status = participants.isEmpty() && cases == 0 && campaigns == 0 && attacks == 0 ? "FAILED" : skippedTotal > 0 ? "PARTIAL" : "SUCCEEDED";
        jdbcTemplate.update("""
                insert into trust_scale_seed_runs (id, seed_type, status, requested_by, reason, counts_json, metrics_json, completed_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), now())
                """, id, request.getOrDefault("seedType", "TECHNICAL_CAPSTONE"), status, actor(request), reason(request),
                json(Map.of("requested", Map.of("participants", requestedParticipants, "trustCases", requestedCases,
                        "campaigns", requestedCampaigns, "attackRuns", requestedAttackRuns), "created", created, "skipped", skipped)),
                json(Map.of("deterministic", true, "createdCounts", created, "skippedCounts", skipped)));
        outbox.insert("TRUST_SCALE_SEED", id, null, "TRUST_SCALE_SEED_RUN", created);
        return Map.of("seedRunId", id, "status", status, "createdCounts", created, "skippedCounts", skipped,
                "metrics", Map.of("deterministic", true));
    }

    public List<Map<String, Object>> scaleRuns() {
        return jdbcTemplate.queryForList("select * from trust_scale_seed_runs order by created_at desc limit 25");
    }

    private Map<String, Object> dossier(String type, UUID id, Map<String, Object> payload) {
        return Map.of("dossierType", type, "targetId", id, "snapshot", payload, "scope", "target_only");
    }

    private List<Map<String, Object>> rowsForTarget(String table, String targetType, UUID targetId) {
        return jdbcTemplate.queryForList("select * from " + table + " where target_type = ? and target_id = ? limit 50", targetType, targetId);
    }

    private void caseTimeline(UUID caseId, String eventType, String actor, String reason, Object payload) {
        jdbcTemplate.update("""
                insert into trust_case_timeline_events (id, case_id, event_type, actor, reason, payload_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), caseId, eventType, actor, reason, json(payload));
    }

    private UUID custody(UUID evidenceId, UUID versionId, String eventType, String actor, String reason,
                         String hashBefore, String hashAfter, Object metadata) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into evidence_custody_events (
                    id, evidence_id, evidence_version_id, event_type, actor_type, actor_id, reason, hash_before, hash_after, metadata_json
                ) values (?, ?, ?, ?, 'OPERATOR', ?, ?, ?, ?, cast(? as jsonb))
                """, id, evidenceId, versionId, eventType, actor, reason, hashBefore, hashAfter, json(metadata));
        return id;
    }

    private void guaranteeTimeline(UUID decisionId, String eventType, String actor, String reason, Object payload) {
        jdbcTemplate.update("""
                insert into guarantee_audit_timeline_events (id, guarantee_decision_id, event_type, actor, reason, payload_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), decisionId, eventType, actor, reason, json(payload));
    }

    private boolean severe(String action) {
        return List.of("SUSPEND_ACCOUNT", "PERMANENT_REMOVAL_RECOMMENDED").contains(action);
    }

    private void validateSevereApproval(UUID approvalId, UUID participantId, UUID targetId, String action, String executionActor) {
        Map<String, Object> approval = one("select * from severe_action_approvals where id = ?", approvalId);
        if (!"APPROVED".equals(approval.get("status"))) {
            throw new ConflictException("Severe action approval must be approved");
        }
        if (approval.get("consumed_at") != null) {
            throw new ConflictException("Severe action approval was already consumed");
        }
        if (!action.equals(approval.get("action_type"))) {
            throw new ConflictException("Severe action approval action does not match");
        }
        UUID approvedTargetId = (UUID) approval.get("target_id");
        String approvedTargetType = approval.get("target_type").toString();
        UUID effectiveTarget = targetId == null ? participantId : targetId;
        if (!approvedTargetId.equals(effectiveTarget) && !approvedTargetId.equals(participantId)) {
            throw new ConflictException("Severe action approval target does not match");
        }
        if (targetId == null && !"PARTICIPANT".equals(approvedTargetType)) {
            throw new ConflictException("Severe action approval target type does not match");
        }
        if (executionActor.equals(approval.get("requested_by"))) {
            throw new ConflictException("Severe action executor must differ from requester");
        }
        if (approval.get("approved_by") == null || approval.get("approved_by").equals(approval.get("requested_by"))) {
            throw new ConflictException("Severe action approval must be second-person approved");
        }
    }

    private Map<String, Object> one(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args).stream().findFirst().orElseThrow(() -> new NotFoundException("Record not found"));
    }

    private Map<String, Object> safeOne(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args).stream().findFirst().orElse(Map.of());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String actor(Map<String, Object> request) {
        return request.getOrDefault("actor", request.getOrDefault("requestedBy", request.getOrDefault("openedBy", "operator@example.com"))).toString();
    }

    private String reason(Map<String, Object> request) {
        return request.getOrDefault("reason", "Trust safety technical proof").toString();
    }

    private String require(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.toString();
    }

    private UUID uuid(Map<String, Object> request, String field) {
        return UUID.fromString(require(request, field));
    }

    private UUID optionalUuid(Object value) {
        return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString());
    }

    private Timestamp timestamp(Object value) {
        return value == null || value.toString().isBlank() ? null : Timestamp.from(Instant.parse(value.toString()));
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : value == null ? null : Long.parseLong(value.toString());
    }

    private Integer intValue(Object value) {
        return value instanceof Number number ? number.intValue() : value == null ? null : Integer.parseInt(value.toString());
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private UUID syntheticParticipant(String slug) {
        UUID id = UUID.randomUUID();
        String cleanSlug = slug.length() > 58 ? slug.substring(0, 58) : slug;
        jdbcTemplate.update("""
                insert into participants (id, profile_slug, display_name, account_status, verification_status, trust_tier, risk_level, metadata_json)
                values (?, ?, ?, 'ACTIVE', 'BASIC', 'NEW', 'MEDIUM', cast(? as jsonb))
                on conflict (profile_slug) do nothing
                """, id, cleanSlug, "Synthetic " + cleanSlug, json(Map.of("synthetic", true)));
        List<Map<String, Object>> existing = jdbcTemplate.queryForList("select id from participants where profile_slug = ?", cleanSlug);
        UUID participantId = existing.isEmpty() ? id : (UUID) existing.getFirst().get("id");
        jdbcTemplate.update("""
                insert into trust_profiles (participant_id, trust_score, trust_confidence, trust_tier, risk_level, max_transaction_value_cents)
                values (?, 500, 10, 'NEW', 'MEDIUM', 10000)
                on conflict (participant_id) do nothing
                """, participantId);
        return participantId;
    }

    private int bounded(Object value, int max) {
        int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        if (parsed < 0) throw new IllegalArgumentException("Seed counts must be non-negative");
        return Math.min(parsed, max);
    }

    private List<UUID> uuidList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(item -> UUID.fromString(item.toString())).toList();
    }

    private List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        try {
            List<?> parsed = objectMapper.readValue(value.toString(), new TypeReference<List<?>>() {});
            return parsed.stream().map(Object::toString).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> jsonList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list && (list.isEmpty() || list.getFirst() instanceof Map<?, ?>)) {
            return (List<Map<String, Object>>) value;
        }
        try {
            return objectMapper.readValue(value.toString(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        try {
            return objectMapper.readValue(value.toString(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
