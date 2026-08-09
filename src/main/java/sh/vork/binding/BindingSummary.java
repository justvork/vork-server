package sh.vork.binding;

import java.util.List;

/**
 * Provider-agnostic Binding catalog item.
 */
public record BindingSummary(
        String bindingId,
        String displayName,
        String providerId,
        List<String> profiles,
        String description
) {}
