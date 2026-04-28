package com.trustgrid.api.policyengine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyRuleRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MATCHED_RULES_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PolicyRuleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID createRule(CreatePolicyRuleRequest request) {
        UUID id = UUID.randomUUID();
        UUID policyVersionId = request.policyVersionId() == null
                ? policyId(request.policyName(), request.policyVersion())
                : request.policyVersionId();
        jdbcTemplate.update("""
                insert into trust_policy_rules (
                    id, policy_version_id, rule_key, rule_type, target_scope, condition_json, action_json, enabled, priority
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?)
                """, id, policyVersionId, request.ruleKey(), request.ruleType(), request.targetScope(),
                json(request.condition() == null ? Map.of() : request.condition()),
                json(request.action() == null ? Map.of() : request.action()),
                request.enabled() == null || request.enabled(), request.priority() == null ? 100 : request.priority());
        return id;
    }

    UUID policyId(String policyName, String policyVersion) {
        return jdbcTemplate.queryForObject("""
                select id from trust_policy_versions where policy_name = ? and policy_version = ?
                """, UUID.class, policyName, policyVersion);
    }

    Map<String, Object> policy(UUID policyVersionId) {
        return jdbcTemplate.queryForMap("select * from trust_policy_versions where id = ?", policyVersionId);
    }

    List<PolicyRuleResponse> rules(String policyName, String policyVersion, String ruleType) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select r.* from trust_policy_rules r
                join trust_policy_versions p on p.id = r.policy_version_id
                where 1=1
                """);
        if (policyName != null && !policyName.isBlank()) {
            sql.append(" and p.policy_name = ? ");
            args.add(policyName);
        }
        if (policyVersion != null && !policyVersion.isBlank()) {
            sql.append(" and p.policy_version = ? ");
            args.add(policyVersion);
        }
        if (ruleType != null && !ruleType.isBlank()) {
            sql.append(" and r.rule_type = ? ");
            args.add(ruleType);
        }
        sql.append(" order by r.priority asc, r.created_at asc ");
        return jdbcTemplate.query(sql.toString(), this::ruleRow, args.toArray());
    }

    public List<PolicyRuleResponse> evaluationRules(String policyName, String policyVersion, String targetType) {
        return jdbcTemplate.query("""
                select r.* from trust_policy_rules r
                join trust_policy_versions p on p.id = r.policy_version_id
                where p.policy_name = ? and p.policy_version = ?
                  and r.enabled = true
                  and r.target_scope in (?, 'GLOBAL', 'CATEGORY')
                order by r.priority asc, r.created_at asc
                """, this::ruleRow, policyName, policyVersion, targetType);
    }

    public List<UUID> sampleTargetIds(String targetType, int limit) {
        String sql = switch (targetType) {
            case "PARTICIPANT" -> "select id from participants order by created_at desc limit ?";
            case "LISTING" -> "select id from marketplace_listings order by created_at desc limit ?";
            case "TRANSACTION" -> "select id from marketplace_transactions order by created_at desc limit ?";
            case "DISPUTE" -> "select id from marketplace_disputes order by opened_at desc limit ?";
            case "REVIEW" -> "select id from marketplace_reviews order by created_at desc limit ?";
            default -> "select id from participants order by created_at desc limit ?";
        };
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getObject("id", UUID.class), limit);
    }

    public Map<String, Object> targetSnapshot(String targetType, UUID targetId) {
        return switch (targetType) {
            case "PARTICIPANT" -> jdbcTemplate.queryForMap("""
                    select id as "targetId", trust_tier as "trustTier", verification_status as "verificationStatus",
                           risk_level as "riskLevel", account_status as "accountStatus", 0 as "valueCents"
                    from participants where id = ?
                    """, targetId);
            case "LISTING" -> jdbcTemplate.queryForMap("""
                    select l.id as "targetId", c.code as "categoryCode", l.risk_tier as "riskTier",
                           coalesce(l.price_amount_cents, l.budget_amount_cents, 0) as "valueCents",
                           p.trust_tier as "trustTier", p.verification_status as "verificationStatus",
                           p.account_status as "accountStatus"
                    from marketplace_listings l
                    join marketplace_categories c on c.id = l.category_id
                    join participants p on p.id = l.owner_participant_id
                    where l.id = ?
                    """, targetId);
            case "TRANSACTION" -> jdbcTemplate.queryForMap("""
                    select t.id as "targetId", t.transaction_type as "transactionType", t.value_amount_cents as "valueCents",
                           p.trust_tier as "trustTier", p.verification_status as "verificationStatus",
                           p.account_status as "accountStatus"
                    from marketplace_transactions t
                    join participants p on p.id = t.provider_participant_id
                    where t.id = ?
                    """, targetId);
            case "DISPUTE" -> jdbcTemplate.queryForMap("""
                    select d.id as "targetId", d.dispute_type as "disputeType", d.status as "disputeStatus",
                           coalesce((select count(*) from evidence_requirements er where er.target_type = 'DISPUTE'
                             and er.target_id = d.id and er.satisfied = false), 0) as "unsatisfiedEvidenceCount"
                    from marketplace_disputes d where d.id = ?
                    """, targetId);
            case "REVIEW" -> jdbcTemplate.queryForMap("""
                    select r.id as "targetId", r.status as "reviewStatus", r.confidence_weight as "confidenceWeight",
                           coalesce((select count(*) from review_suppression_actions s where s.review_id = r.id), 0) as "suppressionCount"
                    from marketplace_reviews r where r.id = ?
                    """, targetId);
            default -> Map.of("targetId", targetId.toString(), "targetType", targetType);
        };
    }

    List<PolicyExceptionCandidate> activeExceptions(String policyName, String policyVersion, String targetType, UUID targetId) {
        expireOldExceptions();
        return jdbcTemplate.query("""
                select id, exception_type from policy_exceptions
                where policy_name = ? and policy_version = ?
                  and target_type = ? and target_id = ?
                  and status = 'ACTIVE' and expires_at > now()
                order by created_at asc
                """, (rs, rowNum) -> new PolicyExceptionCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("exception_type")
                ), policyName, policyVersion, targetType, targetId);
    }

    void expireOldExceptions() {
        jdbcTemplate.update("update policy_exceptions set status = 'EXPIRED' where status = 'ACTIVE' and expires_at <= now()");
    }

    UUID insertDecision(EvaluatePolicyRequest request, String decision, List<Map<String, Object>> matchedRules,
                        List<UUID> exceptionIds, Map<String, Object> explanation) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into policy_decision_logs (
                    id, target_type, target_id, policy_name, policy_version, decision,
                    matched_rules_json, exception_ids_json, explanation_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                """, id, request.targetType(), request.targetId(), request.policyName(), request.policyVersion(),
                decision, json(matchedRules), json(exceptionIds), json(explanation));
        return id;
    }

    Optional<PolicyDecisionResponse> decision(UUID id) {
        return jdbcTemplate.query("""
                select * from policy_decision_logs where id = ?
                """, this::decisionRow, id).stream().findFirst();
    }

    List<PolicyDecisionResponse> decisions(String targetType, UUID targetId) {
        if (targetType != null && targetId != null) {
            return jdbcTemplate.query("""
                    select * from policy_decision_logs where target_type = ? and target_id = ? order by created_at desc
                    """, this::decisionRow, targetType, targetId);
        }
        return jdbcTemplate.query("select * from policy_decision_logs order by created_at desc limit 100", this::decisionRow);
    }

    private PolicyRuleResponse ruleRow(ResultSet rs, int rowNum) throws SQLException {
        return new PolicyRuleResponse(rs.getObject("id", UUID.class), rs.getObject("policy_version_id", UUID.class),
                rs.getString("rule_key"), rs.getString("rule_type"), rs.getString("target_scope"),
                readMap(rs.getString("condition_json")), readMap(rs.getString("action_json")),
                rs.getBoolean("enabled"), rs.getInt("priority"), rs.getTimestamp("created_at").toInstant());
    }

    private PolicyDecisionResponse decisionRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> explanation = readMap(rs.getString("explanation_json"));
        List<UUID> exceptions = readStrings(rs.getString("exception_ids_json")).stream().map(UUID::fromString).toList();
        return new PolicyDecisionResponse(rs.getObject("id", UUID.class), rs.getString("target_type"),
                rs.getObject("target_id", UUID.class), rs.getString("policy_name"), rs.getString("policy_version"),
                rs.getString("decision"), readMatchedRules(rs.getString("matched_rules_json")), exceptions,
                readNestedMap(explanation, "inputSnapshot"), explanation,
                readStringList(explanation.get("recommendedNextSteps")), "deterministic_rules_v1",
                rs.getTimestamp("created_at").toInstant());
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    record PolicyExceptionCandidate(UUID id, String exceptionType) {
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> readMatchedRules(String value) {
        try {
            return objectMapper.readValue(value, MATCHED_RULES_TYPE);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<String> readStrings(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readNestedMap(Map<String, Object> value, String key) {
        Object nested = value.get(key);
        return nested instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> readStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
