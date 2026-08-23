package sh.vork.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic renderer for authorization argument payloads.
 */
public final class AuthorizationArgumentsFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AuthorizationArgumentsFormatter() {
    }

    public static String toApprovalMarkdown(String argumentsJson) {
        String normalized = normalize(argumentsJson);
        Object parsed;
        try {
            parsed = OBJECT_MAPPER.readValue(normalized, Object.class);
        } catch (Exception ignored) {
            return "Exact arguments to be executed:\n\n```json\n" + normalized + "\n```";
        }

        List<String> lines = new ArrayList<>();
        flatten("", parsed, lines);
        if (lines.isEmpty()) {
            return "Exact arguments to be executed:\n\n```json\n" + normalized + "\n```";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Exact arguments to be executed:\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|---|---|\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String normalize(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "{}";
        }
        return argumentsJson;
    }

    private static void flatten(String path, Object value, List<String> lines) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isBlank() ? key : path + "." + key;
                flatten(childPath, entry.getValue(), lines);
            }
            return;
        }

        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                lines.add(row(path, "[]"));
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                String childPath = path + "[" + i + "]";
                flatten(childPath, list.get(i), lines);
            }
            return;
        }

        lines.add(row(path.isBlank() ? "(root)" : path, scalarValue(value)));
    }

    private static String row(String field, String value) {
        return "| " + escape(field) + " | " + escape(value) + " |";
    }

    private static String scalarValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String str) {
            return str;
        }
        return String.valueOf(value);
    }

    private static String escape(String input) {
        String safe = input == null ? "" : input;
        return safe.replace("|", "\\|").replace("\n", "<br>");
    }
}