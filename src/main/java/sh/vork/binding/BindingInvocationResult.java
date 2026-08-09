package sh.vork.binding;

/**
 * Generic binding invocation response payload.
 */
public record BindingInvocationResult(
        int statusCode,
        Object body,
        String contentType
) {}
