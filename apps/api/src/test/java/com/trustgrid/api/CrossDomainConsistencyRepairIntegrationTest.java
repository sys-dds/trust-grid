package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CrossDomainConsistencyRepairIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void crossDomainConsistencyCreatesDedupedFindingsAndRepairRecommendations() {
        UUID participant = createCapableParticipant("cross-" + suffix(), "Cross", "BUY");
        UUID caseId = openTrustCase(participant);
        jdbcTemplate.update("update trust_cases set status = 'ASSIGNED', assigned_to = null where id = ?", caseId);
        UUID campaign = createCampaign();
        UUID action = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into campaign_containment_plans (id, campaign_id, status, proposed_by, reason, blast_radius_json, actions_json)
                values (?, ?, 'APPROVED', 'operator@example.com', 'Inconsistent plan', '{}'::jsonb, '[]'::jsonb)
                """, plan, campaign);
        jdbcTemplate.update("""
                insert into campaign_containment_actions (id, containment_plan_id, action_type, target_type, target_id, status, before_json, after_json)
                values (?, ?, 'HIDE_LISTING', 'LISTING', ?, 'EXECUTED', '{}'::jsonb, '{}'::jsonb)
                """, action, plan, UUID.randomUUID());
        UUID evidence = recordEvidence("PARTICIPANT", participant, participant, "MODERATOR_NOTE", "cross-evidence-" + suffix());
        post("/api/v1/evidence/" + evidence + "/versions", Map.of("hash", "expected", "actor", "operator@example.com", "reason", "Version"), null);
        post("/api/v1/evidence/" + evidence + "/tamper-check", Map.of("expectedHash", "bad", "actor", "operator@example.com", "reason", "Tamper"), null);
        post("/api/v1/consistency/checks/full", actorRisk(), null);
        post("/api/v1/consistency/checks/full", actorRisk(), null);
        assertThat(countRows("select count(*) from consistency_findings where finding_type = 'TRUST_CASE_ASSIGNEE_MISSING' and status = 'OPEN'")).isEqualTo(1);
        assertThat(countRows("select count(*) from consistency_findings where finding_type = 'CAMPAIGN_CONTAINMENT_ACTION_WITHOUT_EXECUTED_PLAN' and status = 'OPEN'")).isEqualTo(1);
        assertThat(countRows("select count(*) from consistency_findings where finding_type = 'CAMPAIGN_CONTAINMENT_ACTION_SNAPSHOT_MISSING' and status = 'OPEN'")).isEqualTo(1);
        assertThat(countRows("select count(*) from consistency_findings where finding_type = 'EVIDENCE_HASH_MISMATCH' and status = 'OPEN'")).isEqualTo(1);
        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        String recommendations = getList("/api/v1/data-repair/recommendations").getBody().toString();
        assertThat(recommendations).contains("REQUEST_TRUST_CASE_TARGET_REVIEW", "REQUEST_CONTAINMENT_REVERSAL_REVIEW", "REQUEST_EVIDENCE_CUSTODY_REVIEW");
        UUID recommendation = (UUID) jdbcTemplate.queryForMap("""
                select id from data_repair_recommendations
                where repair_type = 'REQUEST_CONTAINMENT_REVERSAL_REVIEW'
                order by created_at desc limit 1
                """).get("id");
        assertThat(post("/api/v1/data-repair/recommendations/" + recommendation + "/apply", Map.of("actor", "operator@example.com", "reason", "Apply without ack"), null)
                .getStatusCode().value()).isEqualTo(400);
    }
}
