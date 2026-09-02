package sh.vork.ai.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;

/**
 * Decorator that enforces authorization checks before invoking the underlying tool.
 */
public class SecuredToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SecuredToolCallback.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    public static final String CURRENT_TOOL_CALL_ID_CONTEXT_KEY = "__current_tool_call_id__";

    private final ToolCallback delegate;
    private final AuthorizationRuleEngine ruleEngine;
    private final PreAuthorizationTokenService preAuthorizationTokenService;
    private final ApprovalPolicyRuntimeResolver approvalPolicyRuntimeResolver;
    private final boolean forceAuthorization;

    public SecuredToolCallback(ToolCallback delegate, AuthorizationRuleEngine ruleEngine) {
        this(delegate, ruleEngine, null, null, false);
    }

    public SecuredToolCallback(ToolCallback delegate, AuthorizationRuleEngine ruleEngine, boolean forceAuthorization) {
        this(delegate, ruleEngine, null, null, forceAuthorization);
    }

    public SecuredToolCallback(ToolCallback delegate,
                               AuthorizationRuleEngine ruleEngine,
                               PreAuthorizationTokenService preAuthorizationTokenService,
                               boolean forceAuthorization) {
        this(delegate, ruleEngine, preAuthorizationTokenService, null, forceAuthorization);
    }

    public SecuredToolCallback(ToolCallback delegate,
                               AuthorizationRuleEngine ruleEngine,
                               PreAuthorizationTokenService preAuthorizationTokenService,
                               ApprovalPolicyRuntimeResolver approvalPolicyRuntimeResolver,
                               boolean forceAuthorization) {
        this.delegate = delegate;
        this.ruleEngine = ruleEngine;
        this.preAuthorizationTokenService = preAuthorizationTokenService;
        this.approvalPolicyRuntimeResolver = approvalPolicyRuntimeResolver;
        this.forceAuthorization = forceAuthorization;
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

    private void enforce(String effectiveArguments, ToolContext toolContext) {
        String username = resolveUsername();
        String toolName = delegate.getToolDefinition().name();
        String sessionUuid = ToolExecutionContext.getSessionUuid();
        String toolCallId = resolveToolCallId(toolContext);

        if (preAuthorizationTokenService != null
                && preAuthorizationTokenService.consumeMatchingToken(username, sessionUuid, toolName, effectiveArguments)) {
            log.debug("Pre-authorization accepted [tool={}, user={}, session={}]", toolName, username, sessionUuid);
            return;
        }

        if (ruleEngine.requiresAuthorization(toolName, username, toolCallId, forceAuthorization)) {
            String reasoning = extractReasoning(toolContext);
            String displayArguments = formatForDisplay(effectiveArguments);
            String toolDisplayName = formatToolDisplayName(toolName);
            InteractionFormSchema formSchema = new InteractionFormSchema(
                    "AUTHORIZE_TOOL",
                    "Authorization Required",
                    "Confirm whether this protected tool call should run.",
                    List.of(new FormField(
                            "arguments",
                            "markdown",
                            toolDisplayName,
                            displayArguments,
                            false,
                            FieldSource.CONTEXT,
                            List.of())),
                    List.of(
                            new FormAction("ONCE", "Allow Once", "primary"),
                            new FormAction("SESSION", "Allow for Session", "secondary"),
                            new FormAction("ALWAYS", "Always Allow", "success"),
                            new FormAction("DENIED", "Deny", "danger")));

            ToolSuspensionException.SuspensionCampaign campaign = null;
            if (approvalPolicyRuntimeResolver != null) {
                campaign = approvalPolicyRuntimeResolver.resolveCampaign(sessionUuid, toolName);
            }
            throw new ToolSuspensionException(toolName, effectiveArguments, reasoning, formSchema, campaign);
        }

        if (!ruleEngine.isRolePermitted(toolName, username)) {
            throw new AccessDeniedException(
                "Current role does not have permission to execute tool: " + toolName);
        }
    }

    private String invoke(String arguments, ToolContext toolContext) {
        String sessionUuid = ToolExecutionContext.getSessionUuid();
        if (sessionUuid == null || sessionUuid.isBlank()) {
            sessionUuid = resolveSessionUuid();
        }
        // Track whether the context was already bound by an outer frame (e.g. sendMessage /
        // executeSkillSubLoop). If it was, the outer frame owns cleanup — we must NOT call
        // complete() / clear() or we will destroy its session UUID and break any subsequent
        // tool calls or AI iterations in the same chain.
        boolean wasAlreadyBound = ToolExecutionContext.isBound();
        boolean suspended = false;

        if (sessionUuid != null && !sessionUuid.isBlank()) {
            ToolExecutionContext.bindSessionUuid(sessionUuid);
        }

        String effectiveArguments = resolveArguments(arguments, toolContext);

        try {
            enforce(effectiveArguments, toolContext);
            return toolContext == null
                    ? delegate.call(effectiveArguments)
                    : delegate.call(effectiveArguments, toolContext);
        } catch (ToolSuspensionException ex) {
            suspended = true;
            throw ex;
        } finally {
            if (!suspended && !wasAlreadyBound) {
                // Only clean up the context when we established it. If it was already bound
                // by an outer frame, leave it intact so that subsequent iterations can still
                // read the session UUID and skill-frame restrictions.
                if (ToolExecutionContext.isBound()) {
                    ToolExecutionContext.complete(sessionUuid);
                } else {
                    ToolExecutionContext.clear();
                }
            }
        }
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
                    context.get("toolCallArguments"),
                    context.get("tool_call_arguments"),
                    context.get("input"),
                    context.get("toolInput"),
                    context.get("tool_input"),
                    context.get("payload"),
                    context.get("request"));
            if (fromMap == null) {
                return normalized;
            }
            String candidate = normalizeContextArgumentsCandidate(fromMap);
            if (candidate != null && !candidate.isBlank() && !"{}".equals(candidate.trim())) {
                return candidate;
            }
        } catch (Exception ignored) {
            // Best-effort extraction only.
        }

        return normalized;
    }

    private String formatForDisplay(String argumentsJson) {
        if (delegate instanceof VisualizableTool visualizableTool) {
            try {
                String formatted = visualizableTool.formatAuthorizationDetails(argumentsJson);
                if (formatted != null && !formatted.isBlank()) {
                    String trimmed = formatted.trim();
                    if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
                        return trimmed;
                    }
                    return "```\n" + formatted + "\n```";
                }
            } catch (Exception ignored) {
                // Fall back to raw payload if formatter fails.
            }
        }
        return AuthorizationArgumentsFormatter.toApprovalMarkdown(argumentsJson);
    }

    private static String normalizeArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        return arguments;
    }

    private String resolveToolCallId(ToolContext toolContext) {
        if (toolContext != null) {
            try {
                Object contextObj = toolContext.getClass().getMethod("getContext").invoke(toolContext);
                if (contextObj instanceof Map<?, ?> context) {
                    Object candidate = firstNonNull(
                            context.get("toolCallId"),
                            context.get("tool_call_id"),
                            context.get("toolExecutionId"),
                            context.get("tool_execution_id"),
                            context.get("toolUseId"),
                            context.get("tool_use_id"),
                            context.get("id"));
                    String fromContext = normalizeToolCallIdCandidate(candidate);
                    if (fromContext != null) {
                        return fromContext;
                    }
                }
            } catch (Exception ignored) {
                // Best-effort extraction only.
            }
        }

        Object fromExecutionContext = ToolExecutionContext.get(CURRENT_TOOL_CALL_ID_CONTEXT_KEY);
        return normalizeToolCallIdCandidate(fromExecutionContext);
    }

    private static String normalizeToolCallIdCandidate(Object candidate) {
        if (candidate == null) {
            return null;
        }
        String value = String.valueOf(candidate).trim();
        return value.isBlank() ? null : value;
    }

    private static String normalizeContextArgumentsCandidate(Object candidate) {
        if (candidate == null) {
            return null;
        }

        if (candidate instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            return trimmed;
        }

        if (candidate instanceof Map<?, ?> mapValue) {
            Object nested = firstNonNull(
                    mapValue.get("arguments"),
                    mapValue.get("toolArguments"),
                    mapValue.get("tool_arguments"),
                    mapValue.get("toolCallArguments"),
                    mapValue.get("tool_call_arguments"),
                    mapValue.get("input"),
                    mapValue.get("toolInput"),
                    mapValue.get("tool_input"));
            if (nested != null && nested != candidate) {
                String nestedCandidate = normalizeContextArgumentsCandidate(nested);
                if (nestedCandidate != null && !nestedCandidate.isBlank() && !"{}".equals(nestedCandidate.trim())) {
                    return nestedCandidate;
                }
            }
            return toJson(mapValue);
        }

        if (candidate instanceof Iterable<?> iterable) {
            return toJson(iterable);
        }

        return toJson(candidate);
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static final Set<String> ACRONYMS = Set.of(
            "ssh", "sftp", "ftp", "http", "https", "url", "api", "id", "uuid", "json", "xml");

    static String formatToolDisplayName(String camelCaseName) {
        if (camelCaseName == null || camelCaseName.isBlank()) return "Tool";
        String[] words = camelCaseName.split("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) sb.append(' ');
            if (ACRONYMS.contains(word.toLowerCase())) {
                sb.append(word.toUpperCase());
            } else {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
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

    private static String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            return null;
        }
        return sessionUuid;
    }
}
