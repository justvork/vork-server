package sh.vork.binding;

import java.util.Map;

/**
 * Generic binding invocation request.
 */
public record BindingInvocationRequest(
        String bindingId,
        String profile,
        String operationId,
        Map<String, Object> args,
        String actor
) {}
