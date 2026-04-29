package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustCaseMergeSplitReplayIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void splitMovesSelectedTargetsAndReplayIsDeterministic() {
        UUID p = createCapableParticipant("case-merge-" + suffix(), "Case Merge", "BUY");
        UUID other = createCapableParticipant("case-other-" + suffix(), "Case Other", "BUY");
        UUID source = openTrustCase(p);
        post("/api/v1/trust-cases/" + source + "/targets", Map.of(
                "targetType", "PARTICIPANT",
                "targetId", other.toString(),
                "relationshipType", "RELATED",
                "actor", "operator@example.com",
                "reason", "Second target"
        ), null);
        List<Map<String, Object>> targets = jdbcTemplate.queryForList("select * from trust_case_targets where case_id = ? order by created_at", source);
        UUID movedLink = (UUID) targets.get(0).get("id");
        UUID keptLink = (UUID) targets.get(1).get("id");

        Map<?, ?> split = post("/api/v1/trust-cases/" + source + "/split", Map.of(
                "targetIds", List.of(movedLink.toString()),
                "copyInsteadOfMove", false,
                "actor", "operator@example.com",
                "reason", "Split selected target"
        ), null).getBody();
        UUID newCase = UUID.fromString(split.get("newCaseId").toString());

        assertThat(countRows("select count(*) from trust_case_targets where case_id = ? and id = ?", source, movedLink)).isZero();
        assertThat(countRows("select count(*) from trust_case_targets where case_id = ? and id = ?", newCase, movedLink)).isEqualTo(1);
        assertThat(countRows("select count(*) from trust_case_targets where case_id = ? and id = ?", source, keptLink)).isEqualTo(1);
        assertThat(post("/api/v1/trust-cases/" + source + "/split", Map.of(
                "targetIds", List.of(movedLink.toString()),
                "actor", "operator@example.com",
                "reason", "Invalid split"
        ), null).getStatusCode().value()).isIn(400, 409);

        UUID copyLink = (UUID) jdbcTemplate.queryForMap("select id from trust_case_targets where case_id = ? limit 1", source).get("id");
        Map<?, ?> copy = post("/api/v1/trust-cases/" + source + "/split", Map.of(
                "targetIds", List.of(copyLink.toString()),
                "copyInsteadOfMove", true,
                "actor", "operator@example.com",
                "reason", "Copy selected target"
        ), null).getBody();
        UUID copiedCase = UUID.fromString(copy.get("newCaseId").toString());
        assertThat(countRows("select count(*) from trust_case_targets where case_id = ? and id = ?", source, copyLink)).isEqualTo(1);
        assertThat(countRows("select count(*) from trust_case_targets where case_id = ?", copiedCase)).isEqualTo(1);

        int timelineBefore = countRows("select count(*) from trust_case_timeline_events where case_id = ?", source);
        Map<?, ?> firstReplay = post("/api/v1/trust-cases/" + source + "/replay", Map.of("recordReplayEvent", false), null).getBody();
        Map<?, ?> secondReplay = post("/api/v1/trust-cases/" + source + "/replay", Map.of("recordReplayEvent", false), null).getBody();
        assertThat(firstReplay).isEqualTo(secondReplay);
        assertThat(countRows("select count(*) from trust_case_timeline_events where case_id = ?", source)).isEqualTo(timelineBefore);
        Map<?, ?> recordedReplay = post("/api/v1/trust-cases/" + source + "/replay", Map.of(
                "recordReplayEvent", true,
                "actor", "operator@example.com",
                "reason", "Record replay"
        ), null).getBody();
        assertThat(recordedReplay.get("semanticTimelineEventCount")).isEqualTo(firstReplay.get("semanticTimelineEventCount"));
        assertThat(countRows("select count(*) from trust_case_timeline_events where case_id = ? and event_type = 'TRUST_CASE_SPLIT'", source)).isGreaterThanOrEqualTo(2);
    }
}
