package sh.vork.util;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Generates short lowercase alphanumeric IDs suitable for human-friendly tool aliases.
 */
public final class ToolIdGenerator {

    private ToolIdGenerator() {
    }

    public static String normalizeBase(String source, String fallback) {
        String raw = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }

        String normalized = sb.toString();
        if (normalized.isBlank()) {
            normalized = fallback == null || fallback.isBlank() ? "tool" : fallback;
        }
        if (Character.isDigit(normalized.charAt(0))) {
            normalized = "t" + normalized;
        }
        return normalized;
    }

    public static String unique(String preferredSource,
                                String fallback,
                                Predicate<String> isAvailable) {
        String base = normalizeBase(preferredSource, fallback);
        if (isAvailable.test(base)) {
            return base;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = base + i;
            if (isAvailable.test(candidate)) {
                return candidate;
            }
        }
        return base + System.currentTimeMillis();
    }
}
