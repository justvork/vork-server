package sh.vork.reflection;

import java.util.Locale;

/**
 * Group-level authentication strategy for REST reflections.
 */
public enum ReflectionAuthenticationMode {
    NONE,
    OAUTH;

    public static ReflectionAuthenticationMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return ReflectionAuthenticationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unsupported reflection authentication mode: " + raw);
        }
    }
}
