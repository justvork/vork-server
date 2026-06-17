package sh.vork.orm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Evaluates a subset of MongoDB JSON filters against in-memory documents.
 */
public final class MongoLikeFilterEvaluator {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private MongoLikeFilterEvaluator() {
    }

    public static Map<String, Object> parseFilter(ObjectMapper mapper, String filterJson) {
        try {
            if (filterJson == null || filterJson.isBlank()) {
                return Map.of();
            }
            return mapper.readValue(filterJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new DatabaseException("Failed to parse raw MongoDB filter JSON", ex);
        }
    }

    public static boolean matches(Map<String, Object> document, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object condition = entry.getValue();

            if (key.startsWith("$")) {
                if (!matchesLogical(document, key, condition)) {
                    return false;
                }
                continue;
            }

            PathValue pathValue = resolvePath(document, key);
            if (!matchesField(pathValue, condition)) {
                return false;
            }
        }

        return true;
    }

    private static boolean matchesLogical(Map<String, Object> document, String operator, Object condition) {
        switch (operator) {
            case "$and":
                if (!(condition instanceof List<?> list)) return false;
                return list.stream().allMatch(item -> matches(document, toMap(item)));
            case "$or":
                if (!(condition instanceof List<?> list)) return false;
                return list.stream().anyMatch(item -> matches(document, toMap(item)));
            case "$nor":
                if (!(condition instanceof List<?> list)) return false;
                return list.stream().noneMatch(item -> matches(document, toMap(item)));
            case "$not":
                return !matches(document, toMap(condition));
            default:
                return false;
        }
    }

    private static boolean matchesField(PathValue pathValue, Object condition) {
        if (condition instanceof Map<?, ?> mapCondition && hasOperatorKeys(mapCondition)) {
            return matchesOperatorMap(pathValue, mapCondition);
        }
        return valuesEqual(pathValue.value(), condition);
    }

    private static boolean matchesOperatorMap(PathValue pathValue, Map<?, ?> condition) {
        Object value = pathValue.value();
        boolean exists = pathValue.exists();

        for (Map.Entry<?, ?> entry : condition.entrySet()) {
            String op = String.valueOf(entry.getKey());
            Object expected = entry.getValue();

            switch (op) {
                case "$eq":
                    if (!valuesEqual(value, expected)) return false;
                    break;
                case "$ne":
                    if (valuesEqual(value, expected)) return false;
                    break;
                case "$gt":
                    if (compare(value, expected) <= 0) return false;
                    break;
                case "$gte":
                    if (compare(value, expected) < 0) return false;
                    break;
                case "$lt":
                    if (compare(value, expected) >= 0) return false;
                    break;
                case "$lte":
                    if (compare(value, expected) > 0) return false;
                    break;
                case "$in":
                    if (!(expected instanceof List<?> list)) return false;
                    if (list.stream().noneMatch(candidate -> valuesEqual(value, candidate))) return false;
                    break;
                case "$nin":
                    if (!(expected instanceof List<?> list)) return false;
                    if (list.stream().anyMatch(candidate -> valuesEqual(value, candidate))) return false;
                    break;
                case "$exists": {
                    boolean expectedExists = expected instanceof Boolean b && b;
                    if (exists != expectedExists) return false;
                    break;
                }
                case "$regex": {
                    String source = value == null ? "" : String.valueOf(value);
                    String regex = expected == null ? "" : String.valueOf(expected);
                    Object optionsObj = condition.containsKey("$options") ? condition.get("$options") : "";
                    String options = optionsObj == null ? "" : optionsObj.toString();
                    int flags = options.contains("i") ? Pattern.CASE_INSENSITIVE : 0;
                    if (!Pattern.compile(regex, flags).matcher(source).find()) return false;
                    break;
                }
                case "$options":
                    // handled alongside $regex
                    break;
                case "$not":
                    if (matchesOperatorMap(pathValue, toMap(expected))) return false;
                    break;
                default:
                    return false;
            }
        }

        return true;
    }

    private static boolean hasOperatorKeys(Map<?, ?> map) {
        return map.keySet().stream().anyMatch(k -> String.valueOf(k).startsWith("$"));
    }

    @SuppressWarnings("unchecked")
    private static PathValue resolvePath(Map<String, Object> document, String path) {
        if (document == null || path == null || path.isBlank()) {
            return new PathValue(false, null);
        }

        String[] parts = path.split("\\.");
        Object current = document;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> m) || !m.containsKey(part)) {
                return new PathValue(false, null);
            }
            current = ((Map<String, Object>) m).get(part);
        }

        return new PathValue(true, current);
    }

    private static int compare(Object left, Object right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;

        if (left instanceof Number ln && right instanceof Number rn) {
            return Double.compare(ln.doubleValue(), rn.doubleValue());
        }

        Object nLeft = SearchQuery.normalize(left);
        Object nRight = SearchQuery.normalize(right);
        return SearchQuery.compareValues(nLeft, nRight);
    }

    private static boolean valuesEqual(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }

        if (left instanceof Number ln && right instanceof Number rn) {
            return Double.compare(ln.doubleValue(), rn.doubleValue()) == 0;
        }

        return Objects.equals(left, right);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private record PathValue(boolean exists, Object value) {}
}
