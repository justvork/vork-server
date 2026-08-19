package sh.vork.mcp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum McpTransportMode {
    @JsonAlias({"HTTP_JSON", "HTTP_STREAM"})
    STREAMABLE_HTTP,
    SSE;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static McpTransportMode fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return STREAMABLE_HTTP;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STREAMABLE_HTTP", "HTTP_JSON", "HTTP_STREAM" -> STREAMABLE_HTTP;
            case "SSE" -> SSE;
            default -> throw new IllegalArgumentException("Unknown McpTransportMode: " + raw);
        };
    }

    @JsonValue
    public String toValue() {
        return name();
    }
}