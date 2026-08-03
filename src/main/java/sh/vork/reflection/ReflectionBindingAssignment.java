package sh.vork.reflection;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Maps a reflection tool ID to the list of binding UUIDs it is allowed to use.
 */
public record ReflectionBindingAssignment(
        String reflectionId,
        List<String> bindingUuids
) {

    public ReflectionBindingAssignment {
        if (reflectionId == null) {
            reflectionId = "";
        } else {
            reflectionId = reflectionId.trim();
        }
        if (bindingUuids == null || bindingUuids.isEmpty()) {
            bindingUuids = List.of();
        } else {
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String uuid : bindingUuids) {
                if (uuid == null || uuid.isBlank()) {
                    continue;
                }
                normalized.add(uuid.trim());
            }
            bindingUuids = List.copyOf(normalized);
        }
    }
}
