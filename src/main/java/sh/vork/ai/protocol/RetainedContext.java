package sh.vork.ai.protocol;

import java.util.List;
import java.util.Objects;

/**
 * Structured retained state captured from an AI turn.
 *
 * @param facts concrete information established during the turn
 * @param decisions materially relevant choices made during the turn
 * @param unresolved outstanding questions, blockers, or missing information
 */
public record RetainedContext(
        List<String> facts,
        List<String> decisions,
        List<String> unresolved
) {
    public RetainedContext {
        facts = normalize(facts);
        decisions = normalize(decisions);
        unresolved = normalize(unresolved);
    }

    public static RetainedContext empty() {
        return new RetainedContext(List.of(), List.of(), List.of());
    }

    public boolean hasContent() {
        return !facts.isEmpty() || !decisions.isEmpty() || !unresolved.isEmpty();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
