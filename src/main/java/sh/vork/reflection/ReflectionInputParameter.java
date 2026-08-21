package sh.vork.reflection;

/**
 * Dynamic input parameter metadata for a reflection tool.
 */
public record ReflectionInputParameter(
        String name,
        String type,
        String description,
    boolean required,
    boolean array
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

    public ReflectionInputParameter(String name,
                                    String type,
                                    String description,
                                    boolean required) {
        this(name, type, description, required, false);
    }
}
