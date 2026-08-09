package sh.vork.binding;

/**
 * Operation contract for a binding, including dynamic input/output schemas.
 */
public record BindingOperationContract(
        String operationId,
        String name,
        String description,
        Object inputSchema,
        Object outputSchema,
        String responseContentType
) {}
