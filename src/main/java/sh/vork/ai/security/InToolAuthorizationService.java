package sh.vork.ai.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.request.RequestResponsePolicy;
import sh.vork.orm.DatabaseRepository;
import sh.vork.security.UserRole;
import sh.vork.security.VorkUser;

/**
 * Generic authorization gate that any tool can call before executing a sensitive action.
 */
@Service
public class InToolAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(InToolAuthorizationService.class);

    private final ObjectProvider<AuthorizationRuleEngine> authorizationRuleEngineProvider;
    private final DatabaseRepository<VorkUser> userRepository;

    public InToolAuthorizationService(ObjectProvider<AuthorizationRuleEngine> authorizationRuleEngineProvider,
                                      DatabaseRepository<VorkUser> userRepository) {
        this.authorizationRuleEngineProvider = authorizationRuleEngineProvider;
        this.userRepository = userRepository;
    }

    /**
     * Suspends execution for admin approval when no pre-existing approval rule matches.
     */
    public void requireAdminApproval(String gateToolName,
                                     String argumentsJson,
                                     String title,
                                     String description,
                                     String markdownDetails) {
        String username = resolveUsername();
        String toolCallId = resolveToolCallId();

        AuthorizationRuleEngine authorizationRuleEngine = authorizationRuleEngineProvider.getIfAvailable();
        boolean requiresApproval = true;
        if (authorizationRuleEngine != null) {
            requiresApproval = authorizationRuleEngine.requiresAuthorization(
                gateToolName,
                username,
                toolCallId,
                true);
        }
        if (!requiresApproval) {
            log.debug("In-tool authorization gate satisfied [gateTool={}, user={}, toolCallId={}]",
                    gateToolName, username, toolCallId);
            return;
        }

        InteractionFormSchema schema = new InteractionFormSchema(
                "AUTHORIZE_TOOL",
                title == null || title.isBlank() ? "Authorization Required" : title,
                description == null || description.isBlank()
                        ? "Administrator approval is required before this action can continue."
                        : description,
                List.of(new FormField(
                        "arguments",
                        "markdown",
                        "Requested Action",
                        markdownDetails == null || markdownDetails.isBlank()
                                ? AuthorizationArgumentsFormatter.toApprovalMarkdown(argumentsJson)
                                : markdownDetails,
                        false,
                        FieldSource.CONTEXT,
                        List.of())),
                List.of(
                        new FormAction("ONCE", "Allow Once", "primary"),
                        new FormAction("DENIED", "Deny", "danger")));

        List<String> adminChannels = listEnabledAdminUsernames();
        ToolSuspensionException.SuspensionCampaign campaign = adminChannels.isEmpty()
                ? null
                : new ToolSuspensionException.SuspensionCampaign(
                        adminChannels,
                        RequestResponsePolicy.FIRST,
                        null,
                        true,
                        "Administrator approval required",
                        description,
                        null,
                        null);

        throw new ToolSuspensionException(
                gateToolName,
                argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                description,
                schema,
                campaign);
    }

    public List<String> listEnabledAdminUsernames() {
        LinkedHashSet<String> admins = new LinkedHashSet<>();
        try (var stream = userRepository.list(0, Integer.MAX_VALUE)) {
            stream
                    .filter(user -> user != null && user.uuid() != null && !user.uuid().isBlank())
                    .filter(VorkUser::isEnabled)
                    .filter(user -> UserRole.fromStoredValue(user.role()) == UserRole.ADMIN)
                    .map(VorkUser::uuid)
                    .map(String::trim)
                    .forEach(admins::add);
        }
        return List.copyOf(admins);
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "";
        }
        String normalized = auth.getName().trim();
        if ("anonymousUser".equalsIgnoreCase(normalized)) {
            return "";
        }
        return normalized;
    }

    private static String resolveToolCallId() {
        Object raw = ToolExecutionContext.get(SecuredToolCallback.CURRENT_TOOL_CALL_ID_CONTEXT_KEY);
        if (raw == null) {
            return null;
        }
        String id = String.valueOf(raw).trim();
        if (id.isBlank() || "null".equalsIgnoreCase(id.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return id;
    }
}
