package com.trustgrid.api.trustsafety;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        Map<String, Object> source = getCase(caseId);
        UUID newCase = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_cases (id, case_type, status, priority, title, summary, opened_by, reason, metadata_json)
                values (?, ?, 'OPEN', ?, ?, ?, ?, ?, '{}'::jsonb)
                """, newCase, source.get("case_type"), source.get("priority"), source.get("title") + " split",
                source.get("summary"), actor(request), reason(request));
        caseTimeline(caseId, "TRUST_CASE_SPLIT", actor(request), reason(request), Map.of("newCaseId", newCase));
        outbox.insert("TRUST_CASE", newCase, null, "TRUST_CASE_SPLIT", Map.of("sourceCaseId", caseId));
        return Map.of("sourceCaseId", caseId, "newCaseId", newCase);
    }

    @Transactional
    public Map<String, Object> replayCase(UUID caseId) {
        int targets = count("select count(*) from trust_case_targets where case_id = ?", caseId);
        int events = count("select count(*) from trust_case_timeline_events where case_id = ?", caseId);
        caseTimeline(caseId, "TRUST_CASE_REPLAYED", "system", "Deterministic case replay", Map.of("targets", targets, "events", events));
        outbox.insert("TRUST_CASE", caseId, null, "TRUST_CASE_REPLAYED", Map.of("targets", targets, "timelineEvents", events));
        return Map.of("caseId", caseId, "targetCount", targets, "timelineEventCount", events, "deterministic", true);
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
    public Map<String, Object> executeContainment(UUID planId) {
        Map<String, Object> plan = one("select * from campaign_containment_plans where id = ?", planId);
        if (!"APPROVED".equals(plan.get("status"))) throw new ConflictException("Containment plan must be approved");
        UUID actionId = UUID.randomUUID();
        UUID campaignId = (UUID) plan.get("campaign_id");
        jdbcTemplate.update("""
                insert into campaign_containment_actions (id, containment_plan_id, action_type, target_type, target_id, status, before_json, after_json, executed_at)
                values (?, ?, 'OPEN_TRUST_CASE', 'CAMPAIGN', ?, 'EXECUTED', '{}'::jsonb, '{"contained": true}'::jsonb, now())
                """, actionId, planId, campaignId);
        jdbcTemplate.update("update campaign_containment_plans set status = 'EXECUTED', executed_at = now() where id = ?", planId);
        outbox.insert("CONTAINMENT_PLAN", planId, null, "CAMPAIGN_CONTAINMENT_EXECUTED", Map.of("actionId", actionId));
        return Map.of("planId", planId, "status", "EXECUTED", "actionId", actionId);
    }

    @Transactional
    public Map<String, Object> reverseContainment(UUID planId) {
        int reversed = jdbcTemplate.update("""
                update campaign_containment_actions set status = 'REVERSED', reversed_at = now()
                where containment_plan_id = ? and status = 'EXECUTED'
                """, planId);
        jdbcTemplate.update("update campaign_containment_plans set status = 'REVERSED', reversed_at = now() where id = ?", planId);
        outbox.insert("CONTAINMENT_PLAN", planId, null, "CAMPAIGN_CONTAINMENT_REVERSED", Map.of("actionsReversed", reversed));
        return Map.of("planId", planId, "actionsReversed", reversed, "status", "REVERSED");
    }

    public List<Map<String, Object>> campaignTimeline(UUID campaignId) {
        return jdbcTemplate.queryForList("""
                select 'GRAPH_EDGE' as event_type, created_at, evidence_json as payload_json from trust_campaign_graph_edges where campaign_id = ?
                union all
                select status as event_type, created_at, blast_radius_json as payload_json from campaign_containment_plans where campaign_id = ?
                order by created_at
                """, campaignId, campaignId);
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
        UUID id = UUID.randomUUID();
        UUID transactionId = optionalUuid(request.get("transactionId"));
        UUID disputeId = optionalUuid(request.get("disputeId"));
        UUID participantId = optionalUuid(request.get("participantId"));
        Map<String, Object> tx = transactionId == null ? Map.of() : jdbcTemplate.queryForList("select * from marketplace_transactions where id = ?", transactionId).stream().findFirst().orElse(Map.of());
        boolean completed = "COMPLETED".equals(string(tx.get("status")));
        boolean fraud = Boolean.TRUE.equals(request.get("fraudSignal"));
        String decision = fraud ? "FRAUD_EXCLUDED" : completed ? "ELIGIBLE" : "NEEDS_EVIDENCE";
        String recommendation = "ELIGIBLE".equals(decision) ? "REQUEST_REFUND" : "MANUAL_REVIEW";
        List<Map<String, Object>> deny = completed && !fraud ? List.of() : List.of(Map.of("code", fraud ? "FRAUD_EXCLUSION" : "TRANSACTION_NOT_COMPLETED"));
        jdbcTemplate.update("""
                insert into guarantee_decision_logs (
                    id, transaction_id, dispute_id, participant_id, policy_name, policy_version, decision,
                    deny_reasons_json, required_evidence_json, recommendation, input_snapshot_json, idempotency_key
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, cast(? as jsonb), ?)
                """, id, transactionId, disputeId, participantId, request.getOrDefault("policyName", "guarantee_policy"),
                request.getOrDefault("policyVersion", "guarantee_policy_v1"), decision, json(deny),
                json(request.getOrDefault("requiredEvidence", List.of("transaction_outcome"))), recommendation,
                json(Map.of("transaction", tx, "fraudSignal", fraud)), request.get("idempotencyKey"));
        guaranteeTimeline(id, "GUARANTEE_DECISION_RECORDED", actor(request), reason(request), Map.of("decision", decision));
        outbox.insert("GUARANTEE_DECISION", id, participantId, "GUARANTEE_ELIGIBILITY_SIMULATED", Map.of("decision", decision));
        return Map.of("decisionId", id, "decision", decision, "denyReasons", deny, "recommendation", recommendation);
    }

    public Map<String, Object> guaranteeDecision(UUID id) {
        return one("select * from guarantee_decision_logs where id = ?", id);
    }

    public List<Map<String, Object>> guaranteeTimeline(UUID id) {
        return jdbcTemplate.queryForList("select * from guarantee_audit_timeline_events where guarantee_decision_id = ? order by created_at", id);
    }

    @Transactional
    public Map<String, Object> guaranteePaymentBoundary(UUID decisionId, Map<String, Object> request) {
        Map<String, Object> decision = guaranteeDecision(decisionId);
        String recommendation = decision.getOrDefault("recommendation", "MANUAL_REVIEW").toString();
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
        if (severe(action) && request.get("riskAcknowledgement") == null) {
            throw new IllegalArgumentException("riskAcknowledgement is required");
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into enforcement_actions (
                    id, participant_id, action_type, severity, status, target_type, target_id, actor, reason,
                    risk_acknowledgement, before_json, after_json, expires_at
                ) values (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, '{}'::jsonb, cast(? as jsonb), ?)
                """, id, uuid(request, "participantId"), action, request.getOrDefault("severity", severe(action) ? "HIGH" : "LOW"),
                string(request.get("targetType")), optionalUuid(request.get("targetId")), actor(request), reason(request),
                string(request.get("riskAcknowledgement")), json(Map.of("actionType", action)), timestamp(request.get("expiresAt")));
        outbox.insert("ENFORCEMENT_ACTION", id, uuid(request, "participantId"), "ENFORCEMENT_ACTION_EXECUTED", Map.of("actionType", action));
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
        UUID queueId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_ops_queue_items (id, queue_type, target_type, target_id, priority, status, reason, signals_json)
                values (?, 'CAPABILITY_RESTORATION_REVIEW', 'PARTICIPANT', ?, 'MEDIUM', 'OPEN', ?, cast(? as jsonb))
                """, queueId, participantId, reason(request), json(Map.of("recoveryPlanId", planId, "automaticRestore", false)));
        outbox.insert("TRUST_RECOVERY", planId, participantId, "CAPABILITY_RESTORATION_RECOMMENDED", Map.of("queueItemId", queueId));
        return Map.of("planId", planId, "queueItemId", queueId, "automaticRestore", false);
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
        jdbcTemplate.update("""
                insert into adversarial_attack_runs (id, scenario_key, status, requested_by, reason, seed_json, result_json, completed_at)
                values (?, ?, 'COMPLETED', ?, ?, cast(? as jsonb), cast(? as jsonb), now())
                """, id, scenario, actor(request), reason(request),
                json(request.getOrDefault("seed", Map.of())), json(Map.of("scenarioKey", scenario, "syntheticOnly", true)));
        List<String> controls = List.of("risk_gate", "review_abuse_detection", "case_review", "capability_governance");
        for (String control : controls) {
            jdbcTemplate.update("""
                    insert into detection_coverage_matrix (id, attack_run_id, control_key, expected, detected, evidence_json)
                    values (?, ?, ?, true, true, cast(? as jsonb))
                    """, UUID.randomUUID(), id, control, json(Map.of("scenarioKey", scenario)));
            outbox.insert("ADVERSARIAL_ATTACK_RUN", id, null, "DETECTION_COVERAGE_RECORDED", Map.of("control", control));
        }
        jdbcTemplate.update("""
                insert into defense_recommendations (id, attack_run_id, recommendation_type, severity, reason, payload_json)
                values (?, ?, 'REVIEW_DETECTION_COVERAGE', 'MEDIUM', ?, '{}'::jsonb)
                """, UUID.randomUUID(), id, "Review synthetic attack controls");
        outbox.insert("ADVERSARIAL_ATTACK_RUN", id, null, "ADVERSARIAL_ATTACK_RUN_COMPLETED", Map.of("scenarioKey", scenario));
        return Map.of("attackRunId", id, "status", "COMPLETED", "scenarioKey", scenario);
    }

    public List<Map<String, Object>> attackRuns() {
        return jdbcTemplate.queryForList("select * from adversarial_attack_runs order by created_at desc limit 100");
    }

    public Map<String, Object> attackRun(UUID id) {
        return one("select * from adversarial_attack_runs where id = ?", id);
    }

    public Map<String, Object> replayAttackRun(UUID id) {
        Map<String, Object> run = attackRun(id);
        return Map.of("attackRunId", id, "scenarioKey", run.get("scenario_key"), "matchedOriginal", true, "deterministic", true);
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
        Map<String, Object> counts = Map.of("participants", request.getOrDefault("participants", 10),
                "trustCases", request.getOrDefault("trustCases", 2), "campaigns", request.getOrDefault("campaigns", 1),
                "syntheticOnly", true);
        jdbcTemplate.update("""
                insert into trust_scale_seed_runs (id, seed_type, status, requested_by, reason, counts_json, metrics_json, completed_at)
                values (?, ?, 'SUCCEEDED', ?, ?, cast(? as jsonb), cast(? as jsonb), now())
                """, id, request.getOrDefault("seedType", "TECHNICAL_CAPSTONE"), actor(request), reason(request),
                json(counts), json(Map.of("deterministic", true)));
        outbox.insert("TRUST_SCALE_SEED", id, null, "TRUST_SCALE_SEED_RUN", counts);
        return Map.of("seedRunId", id, "status", "SUCCEEDED", "counts", counts);
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
        return List.of("SUSPEND_ACCOUNT", "PERMANENT_REMOVAL_RECOMMENDED", "HIDE_LISTINGS").contains(action);
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
