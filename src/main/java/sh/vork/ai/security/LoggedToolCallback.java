package sh.vork.ai.security;

import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import sh.vork.ai.context.ToolExecutionContext;

/**
 * Global tool-call logger that provides consistent observability for all tools.
 */
public class LoggedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggedToolCallback.class);

    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\\\"(?:password|secret|token|api[_-]?key|private[_-]?key|clientSecret|authorization)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")");
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-~+/=]+");

    private final ToolCallback delegate;

    public LoggedToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public String call(String arguments) {
        return invoke(arguments, null);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        return invoke(arguments, toolContext);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    private String invoke(String arguments, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        String sessionUuid = resolveSessionUuid();
        String effectiveArguments = sanitizeForLog(resolveArguments(arguments, toolContext));
        long startedAt = System.nanoTime();

        log.debug("ENTER tool call: [tool={}, session={}, args={}]", toolName, sessionUuid, effectiveArguments);

        try {
            String result = toolContext == null ? delegate.call(arguments) : delegate.call(arguments, toolContext);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.debug("EXIT tool call: [tool={}, session={}, success=true, durationMs={}, result={}]",
                    toolName, sessionUuid, durationMs, sanitizeForLog(result));
            return result;
        } catch (Exception ex) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.warn("EXIT tool call: [tool={}, session={}, success=false, durationMs={}, errorType={}, error={}]",
                    toolName,
                    sessionUuid,
                    durationMs,
                    ex.getClass().getSimpleName(),
                    truncate(ex.getMessage()));
            throw ex;
        }
    }

    private static String resolveSessionUuid() {
        String sessionUuid = ToolExecutionContext.getSessionUuid();
        if (sessionUuid != null && !sessionUuid.isBlank()) {
            return sessionUuid;
        }
        sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            return "none";
        }
        return sessionUuid;
    }

    private String resolveArguments(String arguments, ToolContext toolContext) {
        String normalized = normalizeArguments(arguments);
        if (!"{}".equals(normalized)) {
            return normalized;
        }
        if (toolContext == null) {
            return normalized;
        }

        try {
            var method = toolContext.getClass().getMethod("getContext");
            Object contextObj = method.invoke(toolContext);
            if (!(contextObj instanceof Map<?, ?> context)) {
                return normalized;
            }

            Object fromMap = firstNonNull(
                    context.get("arguments"),
                    context.get("toolArguments"),
                    context.get("tool_arguments"),
                    context.get("input"),
                    context.get("toolInput"),
                    context.get("tool_input"));
            if (fromMap == null) {
                return normalized;
            }
            if (fromMap instanceof String str) {
                String candidate = normalizeArguments(str);
                return candidate.isBlank() ? normalized : candidate;
            }
            if (fromMap instanceof Map<?, ?> mapValue) {
                return toJsonLike(mapValue);
            }
        } catch (Exception ignored) {
            // Best-effort extraction only.
        }

        return normalized;
    }

    private static String normalizeArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        return arguments;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String toJsonLike(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(String.valueOf(entry.getKey()).replace("\"", "\\\"")).append('"');
            sb.append(':');
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else {
                sb.append('"').append(String.valueOf(value).replace("\"", "\\\"")).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }
        String masked = SENSITIVE_JSON_FIELD.matcher(value).replaceAll("$1***$3");
        masked = BEARER_VALUE.matcher(masked).replaceAll("$1***");
        return masked;
    }

    private static String truncate(String text) {
        return text == null ? "null" : text;
    }
}