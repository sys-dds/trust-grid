package com.trustgrid.api.policyengine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PolicyRuleEvaluator {

    boolean matches(PolicyRuleResponse rule, Map<String, Object> input) {
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

    String decision(PolicyRuleResponse rule) {
        Object decision = rule.action().get("decision");
        if (decision == null) {
            decision = rule.action().get("action");
        }
        return decision == null ? "ALLOW_WITH_LIMITS" : decision.toString();
    }

    private boolean conditionMatches(Map<?, ?> condition, Map<String, Object> input) {
        String field = String.valueOf(condition.get("field"));
        String operator = String.valueOf(condition.containsKey("operator") ? condition.get("operator") : "equals");
        Object expected = condition.get("value");
        Object actual = input.get(field);
        return switch (operator) {
            case "equals" -> compareString(actual, expected) == 0;
            case "not_equals" -> compareString(actual, expected) != 0;
            case "greater_than" -> compareNumber(actual, expected) > 0;
            case "greater_than_or_equal" -> compareNumber(actual, expected) >= 0;
            case "less_than" -> compareNumber(actual, expected) < 0;
            case "less_than_or_equal" -> compareNumber(actual, expected) <= 0;
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
}
