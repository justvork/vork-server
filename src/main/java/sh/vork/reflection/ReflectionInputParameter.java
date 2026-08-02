package sh.vork.reflection;

/**
 * Dynamic input parameter metadata for a reflection tool.
 */
public record ReflectionInputParameter(
        String name,
        String type,
        String description,
        boolean required
) {

    public ReflectionInputParameter {
        if (name == null) {
            name = "";
        }
        if (type == null || type.isBlank()) {
            type = "string";
        }
        if (description == null) {
            description = "";
        }
    }
}
