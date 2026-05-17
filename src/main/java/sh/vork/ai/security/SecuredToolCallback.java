package sh.vork.ai.security;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Decorator that enforces authorization checks before invoking the underlying tool.
 */
public class SecuredToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AuthorizationRuleEngine ruleEngine;

    public SecuredToolCallback(ToolCallback delegate, AuthorizationRuleEngine ruleEngine) {
        this.delegate = delegate;
        this.ruleEngine = ruleEngine;
    }

    @Override
    public String call(String arguments) {
        enforce(arguments, null);
        return delegate.call(arguments);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        enforce(arguments, toolContext);
        return delegate.call(arguments, toolContext);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    private void enforce(String arguments, ToolContext toolContext) {
        String username = resolveUsername();
        String toolName = delegate.getToolDefinition().name();

        if (ruleEngine.requiresAuthorization(toolName, username, "pending-id")) {
            throw new ToolSuspensionException(toolName, arguments, extractReasoning(toolContext));
        }
    }

    private static String extractReasoning(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }

        try {
            var method = toolContext.getClass().getMethod("getContext");
            Object contextObj = method.invoke(toolContext);
            if (contextObj instanceof java.util.Map<?, ?> context) {
                String fromMap = firstNonBlank(
                        context.get("reasoning"),
                        context.get("justification"),
                        context.get("content"),
                        context.get("text"),
                        context.get("assistantMessage"),
                        context.get("assistant_message"),
                        context.get("message"),
                        context.get("output"));
                if (fromMap != null) {
                    return fromMap;
                }
            }
        } catch (Exception ignored) {
            // Best-effort extraction only.
        }

        return null;
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }
}
