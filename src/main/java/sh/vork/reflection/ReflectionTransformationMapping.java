package sh.vork.reflection;

/**
 * Maps one target tool input parameter either from source output path or constant value.
 */
public record ReflectionTransformationMapping(
        String targetParameter,
        String sourcePath,
        String constantValue
) {

    public ReflectionTransformationMapping {
        if (targetParameter == null) {
            targetParameter = "";
        }
        if (sourcePath == null) {
            sourcePath = "";
        }
        if (constantValue == null) {
            constantValue = "";
        }
    }
}
