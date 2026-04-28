package com.trustgrid.api.policyengine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PolicyRuleEvaluator {

    private static final List<String> NUMERIC_OPERATORS = List.of(
            "greater_than", "greater_than_or_equal", "less_than", "less_than_or_equal"
    );

    public boolean matches(PolicyRuleResponse rule, Map<String, Object> input) {
        Object rawConditions = rule.condition().get("conditions");
        if (rawConditions instanceof List<?> conditions) {
            return conditions.stream()
                    .filter(Map.class::isInstance)
                    .map(condition -> (Map<?, ?>) condition)
                    .allMatch(condition -> conditionMatches(condition, input));
        }
        return rule.condition().entrySet().stream().allMatch(entry -> {
            Object raw = entry.getValue();
            if (raw instanceof Map<?, ?> condition) {
                Object operator = condition.containsKey("operator") ? condition.get("operator") : "equals";
                return conditionMatches(Map.of(
                        "field", entry.getKey(),
                        "operator", operator,
                        "value", condition.get("value")
                ), input);
            }
            return Objects.equals(String.valueOf(input.get(entry.getKey())), String.valueOf(raw));
        });
    }

    public String decision(PolicyRuleResponse rule) {
        Object decision = rule.action().get("decision");
        if (decision == null) {
            decision = rule.action().get("action");
        }
        return decision == null ? "ALLOW_WITH_LIMITS" : decision.toString();
    }

    void validateRule(PolicyRuleResponse rule) {
        validateAction(rule.action());
        validateConditions(rule.condition());
    }

    void validateAction(Map<String, Object> action) {
        Object decision = action.get("decision");
        if (decision == null) {
            decision = action.get("action");
        }
        if (decision == null || decision.toString().isBlank()) {
            throw new IllegalArgumentException("action_json.decision or action_json.action is required");
        }
        if (!allowedDecisions().contains(decision.toString())) {
            throw new IllegalArgumentException("Unsupported policy decision: " + decision);
        }
    }

    void validateConditions(Map<String, Object> conditionJson) {
        Object rawConditions = conditionJson.get("conditions");
        if (rawConditions instanceof List<?> conditions) {
            for (Object raw : conditions) {
                if (raw instanceof Map<?, ?> condition) {
                    validateCondition(condition);
                }
            }
            return;
        }
        for (Map.Entry<String, Object> entry : conditionJson.entrySet()) {
            Object raw = entry.getValue();
            if (raw instanceof Map<?, ?> condition) {
                validateCondition(condition);
            }
        }
    }

    private void validateCondition(Map<?, ?> condition) {
        String operator = String.valueOf(condition.containsKey("operator") ? condition.get("operator") : "equals");
        if (NUMERIC_OPERATORS.contains(operator) && !isNumeric(condition.get("value"))) {
            throw new IllegalArgumentException("Numeric policy condition value must be numeric for operator " + operator);
        }
    }

    private boolean conditionMatches(Map<?, ?> condition, Map<String, Object> input) {
        String field = String.valueOf(condition.get("field"));
        String operator = String.valueOf(condition.containsKey("operator") ? condition.get("operator") : "equals");
        Object expected = condition.get("value");
        Object actual = input.get(field);
        return switch (operator) {
            case "equals" -> compareString(actual, expected) == 0;
            case "not_equals" -> compareString(actual, expected) != 0;
            case "greater_than" -> isNumeric(actual) && isNumeric(expected) && compareNumber(actual, expected) > 0;
            case "greater_than_or_equal" -> isNumeric(actual) && isNumeric(expected) && compareNumber(actual, expected) >= 0;
            case "less_than" -> isNumeric(actual) && isNumeric(expected) && compareNumber(actual, expected) < 0;
            case "less_than_or_equal" -> isNumeric(actual) && isNumeric(expected) && compareNumber(actual, expected) <= 0;
            case "in" -> expected instanceof List<?> values && values.stream().anyMatch(value -> compareString(actual, value) == 0);
            case "contains" -> actual != null && expected != null && actual.toString().contains(expected.toString());
            case "exists" -> actual != null;
            default -> false;
        };
    }

    private int compareString(Object actual, Object expected) {
        return String.valueOf(actual).compareTo(String.valueOf(expected));
    }

    private int compareNumber(Object actual, Object expected) {
        double left = actual instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(actual));
        double right = expected instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(expected));
        return Double.compare(left, right);
    }

    private boolean isNumeric(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value == null) {
            return false;
        }
        try {
            Double.parseDouble(String.valueOf(value));
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private List<String> allowedDecisions() {
        return List.of("ALLOW", "ALLOW_WITH_LIMITS", "REQUIRE_EXTRA_EVIDENCE", "REQUIRE_VERIFICATION",
                "REQUIRE_MANUAL_REVIEW", "HIDE_LISTING", "BLOCK_TRANSACTION", "RESTRICT_CAPABILITY",
                "SUSPEND_ACCOUNT", "SUPPRESS_REVIEW_WEIGHT");
    }
}
