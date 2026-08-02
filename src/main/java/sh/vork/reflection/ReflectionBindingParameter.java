package sh.vork.reflection;

/**
 * Group-level parameter definition used by bindings.
 */
public record ReflectionBindingParameter(
        String name,
        String type,
        String description,
        String defaultValue
) {

    public ReflectionBindingParameter {
        if (name == null) {
            name = "";
        }
        if (type == null || type.isBlank()) {
            type = "string";
        }
        if (description == null) {
            description = "";
        }
        if (defaultValue == null) {
            defaultValue = "";
        }
    }
}
