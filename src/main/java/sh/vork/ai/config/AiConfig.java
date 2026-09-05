package sh.vork.ai.config;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.Base64DecodeStringRequest;
import sh.vork.ai.function.Base64EncodeStringRequest;
import sh.vork.ai.function.CheckProcessRequest;
import sh.vork.ai.function.CompileTypeRequest;
import sh.vork.ai.function.ConfigureEncryptionRequest;
import sh.vork.ai.function.CreateAttentionAlertToolRequest;
import sh.vork.ai.function.CreateFolderRequest;
import sh.vork.ai.function.CreateMongoDbConnectionRequest;
import sh.vork.ai.function.CreatePdfRequest;
import sh.vork.ai.function.CreateSessionTextFileRequest;
import sh.vork.ai.function.CreateSkillRequest;
import sh.vork.ai.function.DeleteMongoDbDocumentsRequest;
import sh.vork.ai.function.DeleteSshConnectionRequest;
import sh.vork.ai.function.DecryptStringRequest;
import sh.vork.ai.function.DesignSkillRequest;
import sh.vork.ai.function.DisconnectSshRequest;
import sh.vork.ai.function.DiscoverExportableTypesRequest;
import sh.vork.ai.function.DownloadFileRequest;
import sh.vork.ai.function.DownloadFolderAsZipRequest;
import sh.vork.ai.function.EncryptStringRequest;
import sh.vork.ai.function.ExecuteCommandAndOutputRequest;
import sh.vork.ai.function.ExecuteTerminalCommandRequest;
import sh.vork.ai.function.ExportAllJavaTypeDataRequest;
import sh.vork.ai.function.ExportJavaTypeRequest;
import sh.vork.ai.function.ExportJavaTypeSourceRequest;
import sh.vork.ai.function.ExtractZipRequest;
import sh.vork.ai.function.FileExistsRequest;
import sh.vork.ai.function.FolderExistsRequest;
import sh.vork.ai.function.GeneratePrivateKeyRequest;
import sh.vork.ai.function.GetDateTimeRequest;
import sh.vork.ai.function.GetMongoDbCollectionSchemaRequest;
import sh.vork.ai.function.GetPublicKeyRequest;
import sh.vork.ai.function.GetSurfaceReflectionContractsRequest;
import sh.vork.ai.function.GetTypeSchemaRequest;
import sh.vork.ai.function.HttpRequestToolRequest;
import sh.vork.ai.function.InsertMongoDbDocumentRequest;
import sh.vork.ai.function.InstallCommandRequest;
import sh.vork.ai.function.IsCommandInstalledRequest;
import sh.vork.ai.function.ListAgentTemplatesRequest;
import sh.vork.ai.function.ListAvailableToolsRequest;
import sh.vork.ai.function.ListEnumValuesRequest;
import sh.vork.ai.function.ListFilesRequest;
import sh.vork.ai.function.ListJavaTypesRequest;
import sh.vork.ai.function.ListMongoDbCollectionsRequest;
import sh.vork.ai.function.ListNotificationLedgerEntriesRequest;
import sh.vork.ai.function.ListNotificationProvidersRequest;
import sh.vork.ai.function.ListSshConnectionsRequest;
import sh.vork.ai.function.LogInfoRequest;
import sh.vork.ai.function.ReadFileRequest;
import sh.vork.ai.function.ReadProcessRequest;
import sh.vork.ai.function.RequestAuthorizationToolRequest;
import sh.vork.ai.function.RequestInformationToolRequest;
import sh.vork.ai.function.ResolveArchitectureRequest;
import sh.vork.ai.function.SearchMongoDbDocumentsRequest;
import sh.vork.ai.function.SendNotificationRequest;
import sh.vork.ai.function.SetSshAliasRequest;
import sh.vork.ai.function.SignDataRequest;
import sh.vork.ai.function.SshConnectRequest;
import sh.vork.ai.function.SshCreateConnectionRequest;
import sh.vork.ai.function.StartProcessRequest;
import sh.vork.ai.function.StopProcessRequest;
import sh.vork.ai.function.SummarizeNotificationLedgerRequest;
import sh.vork.ai.function.UpdateMongoDbDocumentsRequest;
import sh.vork.ai.function.UploadFileRequest;
import sh.vork.ai.function.UploadTextFileRequest;
import sh.vork.ai.function.VerifyDataByRefRequest;
import sh.vork.ai.function.VerifyDataRequest;
import sh.vork.ai.function.WriteBase64FileRequest;
import sh.vork.ai.function.WriteFileRequest;
import sh.vork.ai.function.WriteProcessRequest;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.mongo.MongoToolService;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.registry.Hidden;
import sh.vork.ai.registry.ToolCategory;
import sh.vork.ai.registry.ToolDepends;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.ai.security.encrypt.EncryptionService;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.security.ApprovalPolicyRuntimeResolver;
import sh.vork.ai.security.ApprovalPolicy;
import sh.vork.ai.security.ApprovalPolicyService;
import sh.vork.ai.security.LoggedToolCallback;
import sh.vork.ai.security.InToolAuthorizationService;
import sh.vork.ai.security.PreAuthorizationTokenService;
import sh.vork.ai.security.Restricted;
import sh.vork.ai.security.SecuredToolCallback;
import sh.vork.ai.security.VisualizableToolCallback;
import sh.vork.ai.service.AgentAssignmentService;
import sh.vork.ai.request.RequestInformationService;
import sh.vork.ai.request.RequestCampaignStatus;
import sh.vork.ai.request.RequestResponsePolicy;
import sh.vork.ai.skill.SkillAuthoringService;
import sh.vork.attention.AttentionAlert;
import sh.vork.attention.AttentionAlertService;
import sh.vork.attention.AttentionResolutionPolicy;
import sh.vork.attention.AttentionSourceType;
import sh.vork.channel.ChannelRef;
import sh.vork.channel.ChannelService;
import sh.vork.ai.tool.CheckProcessTool;
import sh.vork.ai.tool.CompleteBackgroundTaskRequest;
import sh.vork.ai.tool.CompleteSkillExecutionRequest;
import sh.vork.ai.tool.ConfigureSessionApprovalRequest;
import sh.vork.ai.tool.CreateSessionTextFileTool;
import sh.vork.ai.tool.DefineKnowledgeRequest;
import sh.vork.ai.tool.DelegateTaskRequest;
import sh.vork.ai.tool.DeleteSshConnectionTool;
import sh.vork.ai.tool.DisconnectSshTool;
import sh.vork.ai.tool.DownloadFileTool;
import sh.vork.ai.tool.ExecuteCommandAndOutputTool;
import sh.vork.ai.tool.ExecuteTerminalCommandTool;
import sh.vork.ai.tool.GetKnowledgeRequest;
import sh.vork.ai.tool.ListSshConnectionsTool;
import sh.vork.ai.tool.MemoryRequest;
import sh.vork.ai.tool.ReadProcessTool;
import sh.vork.ai.tool.RecordProgressRequest;
import sh.vork.ai.tool.SearchKnowledgeRequest;
import sh.vork.ai.tool.SessionFileToolSuite;
import sh.vork.ai.tool.SetSshAliasTool;
import sh.vork.ai.tool.SshConnectTool;
import sh.vork.ai.tool.SshCreateConnectionTool;
import sh.vork.ai.tool.StartProcessTool;
import sh.vork.ai.tool.StopProcessTool;
import sh.vork.ai.tool.ThinkRequest;
import sh.vork.ai.tool.ToggleInputRelayRequest;
import sh.vork.ai.tool.UploadFileTool;
import sh.vork.ai.tool.UploadTextFileTool;
import sh.vork.ai.tool.WriteProcessTool;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.knowledge.KnowledgeEntry;
import sh.vork.knowledge.KnowledgeService;
import sh.vork.notification.NotificationLedgerEntry;
import sh.vork.notification.service.DirectNotificationService;
import sh.vork.oauth.OAuthClientService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.JobResult;
import sh.vork.artifact.ArtifactStatus;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.scheduling.service.BackgroundExecutionContext;
import sh.vork.security.SecureCredentialStore;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillService;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.TypeExportService;
import sh.vork.typegen.TypeGenerationException;
import sh.vork.typegen.TypeGeneratorService;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceReflectionContractService;

/**
 * Wires all AI-related Spring beans.
 *
 * <h3>How the routing works</h3>
 * Each supported provider gets its own {@code @Bean ChatClient}. All clients
 * are collected into a single {@code Map<AiProvider, ChatClient>} registry
 * bean.
 * {@code AiOrchestrationService} resolves the correct client at call-time by
 * looking up the caller-supplied {@link AiProvider} key.
 *
 * <h3>Adding a new provider</h3>
 * <ol>
 * <li>Add the enum entry in {@link AiProvider}.</li>
 * <li>Add a {@code @Bean ChatClient} here (inject the provider's
 * auto-configured
 * {@code ChatModel}).</li>
 * <li>Add an entry in {@link #chatClientRegistry}.</li>
 * </ol>
 * No other class needs to change.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);
    public static final String BASE_SYSTEM_PROMPT = """
You are an autonomous Vork agent operating strictly within a turn-based AI 
orchestration framework. You execute background workflows using function-calling 
tools. You do not text-chat with a user.

CRITICAL PROCESSING PROTOCOL (HIGHEST PRIORITY):

1. ABSOLUTE SILENCE OUTSIDE OF TOOLS: 
   You are forbidden from generating free-form, conversational text responses 
   (e.g., "I will do this," "Processing now," "Sure, let me check"). Every 
   single turn must consist ONLY of tool calls, or the final JSON response. 

2. ONE TURN = COMPLETE EXECUTION:
   Do not pause for user confirmation or give status updates in raw text. If 
   you need to perform actions, you must chain or invoke those tool calls 
   immediately in this turn. 

3. THE "THINK" TOOL RULES:
   - If you need to reason, log progress, vent, or describe your next steps, 
   you MUST use the `think` tool.
   - The `think` tool is for mid-turn internal commentary only. 
   - CRITICAL: A `think` call is never a final action. If you call `think`, 
   you MUST immediately invoke an action tool or complete your data processing 
   in the same turn. You may never end a turn with a `think` call as your 
   standalone or final output.

3b. THE "recordProgress" TOOL RULES:
    - Use `recordProgress` whenever you complete an important checkpoint whose
    state may be needed in later turns (e.g., hosts scanned, artifacts generated,
    report sent).
    - Keep entries concise and factual.
    - `recordProgress` is persistent session memory, not a final action; continue
    execution after recording progress.

3c. THE "memory" TOOL RULES:
    - Use `memory` to store stable key=value context you must reuse in later turns
    (e.g., active_target_alias, selected_profile, ticket_id).
    - Use `memory` with operation=set to persist a value, operation=get/list to
    retrieve values, and operation=delete to remove stale values.
    - Keys and values are injected into future system prompts as session environment
    variables, so keep them concise and machine-readable.

4. TURN COMPLETION (FINISHED_TURN):
   - You may only return the final JSON payload with status "FINISHED_TURN" 
   when you have fully executed the request and have the actual, substantive
   data/result ready.
   - NEVER emit "FINISHED_TURN" with an empty, placeholder, or status-only 
   text response (e.g., "I am about to scan..."). 
   - If the final data is not ready, you are NOT finished. Use your action 
   tools or the think tool to get it.

OUTPUT FORMAT ENFORCEMENT:
Your output must strictly be either:
A) Valid tool invocation syntax (including `think` combined with action tools).
B) The final JSON structure with status "FINISHED_TURN" and the textResponse field 
containing the actual results.

Any conversational preamble or postamble outside of these structures violates 
the protocol and will break the system. Do not converse. Execute.
                                """.stripIndent();
    private final JavaTypeClassLoader typeClassLoader;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiConfig(JavaTypeClassLoader typeClassLoader,
            ObjectMapper objectMapper) {
        this.typeClassLoader = typeClassLoader;
        this.objectMapper = objectMapper;
    }

    public AiConfig(JavaTypeClassLoader typeClassLoader,
            TypeDatabaseService ignoredTypeDatabaseService,
            ObjectMapper objectMapper) {
        this(typeClassLoader, objectMapper);
    }

    // -------------------------------------------------------------------------
    // ChatClient beans
    // -------------------------------------------------------------------------
    // All providers (Gemini, OpenAI, Ollama, Groq) are built programmatically
    // by AiChatClientFactory from credentials stored via the setup UI.
    // No auto-configured ChatClient beans are needed here.

    private static boolean isRestrictedTool(ConfigurableListableBeanFactory beanFactory, String toolName) {
        return readBeanMethodAnnotation(beanFactory, toolName, Restricted.class) != null;
    }

    private static boolean isHiddenTool(ConfigurableListableBeanFactory beanFactory, String toolName) {
        return readBeanMethodAnnotation(beanFactory, toolName, Hidden.class) != null;
    }

    private static <A extends java.lang.annotation.Annotation> A readBeanMethodAnnotation(
            ConfigurableListableBeanFactory beanFactory, String toolName, Class<A> annotationType) {
        if (!beanFactory.containsBeanDefinition(toolName)) {
            return null;
        }
        BeanDefinition bd = beanFactory.getBeanDefinition(toolName);
        String factoryBeanName = bd.getFactoryBeanName();
        String factoryMethodName = bd.getFactoryMethodName();
        if (factoryBeanName == null || factoryMethodName == null) {
            return null;
        }
        try {
            Object factoryBean = beanFactory.getBean(factoryBeanName);
            Class<?> targetClass = ClassUtils.getUserClass(factoryBean);
            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.getName().equals(factoryMethodName)
                        && method.isAnnotationPresent(annotationType)) {
                    return method.getAnnotation(annotationType);
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Provider registry (always empty at startup — AiChatClientFactory builds
    // clients lazily from credentials stored via the setup UI)
    // -------------------------------------------------------------------------

    @Bean
    public Map<AiProvider, ChatClient> chatClientRegistry() {
        return new LinkedHashMap<>();
    }

    // -------------------------------------------------------------------------
    // Function-calling tools
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Secured tool-callback map (for per-request tool filtering)
    // -------------------------------------------------------------------------

    /**
     * A map of every registered {@link ToolCallback} bean, keyed by Spring bean name,
     * with {@link sh.vork.ai.security.Restricted} beans wrapped in
     * {@link sh.vork.ai.security.SecuredToolCallback}.
     *
     * <p>This map is consumed by {@link sh.vork.ai.service.AiOrchestrationService}
     * to filter tool callbacks at request time when an
     * {@link sh.vork.ai.agent.AgentTemplate} restricts the allowed tool set.
     */
    @Bean
    public Map<String, ToolCallback> securedToolCallbackMap(
            List<ToolCallback> toolCallbacks,
            AuthorizationRuleEngine authorizationRuleEngine,
            PreAuthorizationTokenService preAuthorizationTokenService,
            ApprovalPolicyRuntimeResolver approvalPolicyRuntimeResolver,
            ConfigurableListableBeanFactory beanFactory) {
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        toolCallbacks.forEach(tool -> {
            String toolName = tool.getToolDefinition().name();
            if (isHiddenTool(beanFactory, toolName)) {
                return; // hidden tools are injected per-session via SessionToolStore
            }
            ToolCallback wrapped = isRestrictedTool(beanFactory, toolName)
                    ? new SecuredToolCallback(tool, authorizationRuleEngine, preAuthorizationTokenService, approvalPolicyRuntimeResolver, false)
                    : tool;
            map.put(toolName, new LoggedToolCallback(wrapped));
        });
        return map;
    }

    /**
     * {@code listAgentTemplates} tool — returns all configured {@link AgentTemplate} records.
     */
    @Bean
    @Restricted
    @ToolCategory("Authorization")
    public ToolCallback requestAuthorization(PreAuthorizationTokenService preAuthorizationTokenService,
                                             ConfigurableListableBeanFactory beanFactory) {
        return FunctionToolCallback
                .builder("requestAuthorization", (RequestAuthorizationToolRequest req) -> {
                    if (req == null) {
                        return "{\"status\":\"error\",\"message\":\"request is required\"}";
                    }
                    if (req.toolName() == null || req.toolName().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"toolName is required\"}";
                    }
                    if (req.argumentsJson() == null || req.argumentsJson().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"argumentsJson is required\"}";
                    }

                    String targetTool = req.toolName().trim();
                    boolean restricted = isRestrictedTool(beanFactory, targetTool);
                    if (!restricted) {
                        return "{\"status\":\"error\",\"message\":\"toolName is not restricted: "
                                + targetTool.replace("\"", "'") + "\"}";
                    }

                    String username = resolveUsername();
                    String sessionUuid = resolveSessionUuid();
                    if ((username == null || username.isBlank()) && sessionUuid != null && !sessionUuid.isBlank()) {
                        username = "session:" + sessionUuid;
                    }
                    if (username == null || username.isBlank()) {
                        username = "anonymous";
                    }

                    PreAuthorizationTokenService.IssuedToken issued = preAuthorizationTokenService.issueToken(
                            username,
                            sessionUuid,
                            targetTool,
                            req.argumentsJson(),
                            req.ttlSeconds(),
                            req.scope(),
                            req.reason());

                    try {
                        return objectMapper.writeValueAsString(Map.of(
                                "status", "ok",
                                "preAuthorizationToken", issued.token(),
                                "toolName", issued.toolName(),
                                "scope", issued.scope(),
                                "argumentsSha256", issued.argumentsSha256(),
                                "expiresAt", issued.expiresAt()));
                    } catch (Exception ex) {
                        return "{\"status\":\"error\",\"message\":\""
                                + ex.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Create a pre-authorization token for an exact restricted tool payload. "
                        + "This tool is itself restricted and requires explicit approval. "
                        + "Tokens are one-time and consumed only when the target restricted tool is called with an exact payload match.")
                .inputType(RequestAuthorizationToolRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Agent Orchestration")
    public ToolCallback listAgentTemplates(DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                           AgentAssignmentService agentAssignmentService,
                                           DatabaseRepository<AiSession> aiSessionRepository) {
        return FunctionToolCallback
                .builder("listAgentTemplates", (ListAgentTemplatesRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    String username = resolveUsername();
                    if ((username == null || username.isBlank())
                            && sessionUuid != null
                            && !sessionUuid.isBlank()
                            && !"system".equals(sessionUuid)) {
                        AiSession session = aiSessionRepository.get(sessionUuid);
                        if (session != null && session.username() != null && !session.username().isBlank()) {
                            username = session.username();
                        }
                    }
                    List<Object> entries = new ArrayList<>();
                    try (var stream = agentTemplateRepository.list(0, Integer.MAX_VALUE)) {
                        String effectiveUsername = username;
                        stream
                            .filter(t -> effectiveUsername != null
                                    && !effectiveUsername.isBlank()
                                    && !t.hidden()
                                    && agentAssignmentService.isAssignedToUser(t, effectiveUsername))
                            .forEach(t -> entries.add(java.util.Map.of(
                                "uuid",         t.uuid(),
                                "name",         t.name(),
                                "agentType",    t.agentType().name(),
                                "systemPrompt", t.systemPrompt(),
                                "allowedTools", t.allowedTools())));
                    }
                    try {
                        return objectMapper.writeValueAsString(entries);
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("""
                    List all configured agent templates. Returns each template's UUID, name, \
                    system prompt, and the list of allowed tool bean IDs."""
                        .stripIndent())
                .inputType(ListAgentTemplatesRequest.class)
                .build();
    }

    /**
     * {@code listAvailableTools} tool — returns the full registered tool catalog from
     * {@link sh.vork.ai.registry.ToolRegistry}.
     */
    @Bean
    @Hidden
    @ToolCategory("Agent Orchestration")
    public ToolCallback listAvailableTools(ToolRegistry toolRegistry,
                                           ObjectProvider<sh.vork.ai.service.AiOrchestrationService> aiOrchestrationServiceProvider) {
        return FunctionToolCallback
                .builder("listAvailableTools", (ListAvailableToolsRequest req) -> {
                    List<Object> entries = new ArrayList<>();

                    String sessionUuid = resolveSessionUuid();
                    java.util.Set<String> visibleToolIds = java.util.Set.of();
                    var aiOrchestrationService = aiOrchestrationServiceProvider.getIfAvailable();
                    boolean applySessionFilter = aiOrchestrationService != null
                        && sessionUuid != null
                        && !sessionUuid.isBlank()
                        && !"system".equals(sessionUuid);
                    if (applySessionFilter) {
                        visibleToolIds = aiOrchestrationService.resolveVisibleToolNamesForSession(sessionUuid);
                    }

                    boolean effectiveApplySessionFilter = applySessionFilter;
                    java.util.Set<String> effectiveVisibleToolIds = visibleToolIds;
                    toolRegistry.getAvailableTools().stream()
                        .filter(d -> !effectiveApplySessionFilter || effectiveVisibleToolIds.contains(d.id()))
                            .forEach(d -> entries.add(java.util.Map.of(
                                    "id",          d.id(),
                                    "name",        d.name(),
                                    "description", d.description(),
                                    "parameterSchema", d.parameterSchema(),
                                    "dependsOn", d.dependsOn())));
                    try {
                        return objectMapper.writeValueAsString(entries);
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("""
                    List all registered tool callbacks with their IDs and descriptions. Use this \
                    to discover valid tool IDs when building or reviewing an AgentTemplate's \
                    allowedTools list."""
                        .stripIndent())
                .inputType(ListAvailableToolsRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Surface")
    public ToolCallback getSurfaceReflectionContracts(SurfaceReflectionContractService contractService) {
        return FunctionToolCallback
                .builder("getSurfaceReflectionContracts", (GetSurfaceReflectionContractsRequest req) -> {
                    try {
                        String sessionUuid = resolveSessionUuid();
                        String requestedSurfaceUuid = req == null ? null : req.surfaceUuid();
                        String requestedBindingGroupToolId = req == null ? null : req.bindingGroupToolId();
                        String requestedBindingProfileName = req == null ? null : req.bindingProfileName();

                        Surface resolvedSurface = null;
                        boolean resolvedFromSession = false;
                        if (sessionUuid != null && !sessionUuid.isBlank() && !"system".equals(sessionUuid)) {
                            resolvedSurface = contractService.findSurfaceBySessionUuid(sessionUuid);
                            resolvedFromSession = resolvedSurface != null;
                        }

                        if (resolvedSurface == null) {
                            if (requestedSurfaceUuid == null || requestedSurfaceUuid.isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"No surface is linked to this session. Provide surfaceUuid or surfaceId.\"}";
                            }
                            resolvedSurface = contractService.resolveSurfaceByUuidOrToolId(requestedSurfaceUuid.trim());
                        }

                        if (resolvedSurface == null) {
                            return "{\"status\":\"error\",\"message\":\"Surface not found.\"}";
                        }

                        if (resolvedFromSession
                                && requestedSurfaceUuid != null
                                && !requestedSurfaceUuid.isBlank()
                                && !resolvedSurface.uuid().equals(requestedSurfaceUuid.trim())
                                && !resolvedSurface.toolId().equalsIgnoreCase(requestedSurfaceUuid.trim())) {
                            log.debug("Ignoring mismatched requested surface identifier because active session surface is authoritative [requested={}, activeUuid={}, activeToolId={}]",
                                    requestedSurfaceUuid, resolvedSurface.uuid(), resolvedSurface.toolId());
                        }

                        var response = contractService.contractsForSurface(
                                resolvedSurface.uuid(),
                            requestedBindingGroupToolId == null ? null : requestedBindingGroupToolId.trim(),
                            requestedBindingProfileName == null ? null : requestedBindingProfileName.trim());
                        return objectMapper.writeValueAsString(response);
                    } catch (IllegalArgumentException ex) {
                        return "{\"status\":\"error\",\"message\":\""
                                + ex.getMessage().replace("\"", "'") + "\"}";
                    } catch (Exception ex) {
                        return "{\"status\":\"error\",\"message\":\""
                                + ex.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Return input/output contracts for reflections attached to the current surface session. Call this before generating UI code that invokes reflections.")
                .inputType(GetSurfaceReflectionContractsRequest.class)
                .build();
    }

    /**
     * Read-only skill-authoring helper that converts a natural-language request
     * into a feasibility assessment and draft skill configuration.
     */
    @Bean
    @ToolCategory("Skills")
    public ToolCallback designSkillFromRequest(ObjectProvider<SkillAuthoringService> skillAuthoringServiceProvider) {
        return FunctionToolCallback
                .builder("designSkillFromRequest", (DesignSkillRequest req) -> {
                    SkillAuthoringService skillAuthoringService = skillAuthoringServiceProvider.getObject();
                    SkillAuthoringService.SkillAuthoringResult result =
                            skillAuthoringService.designSkillFromRequest(resolveUsername(), req);
                    try {
                        return objectMapper.writeValueAsString(result);
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Analyze a natural-language skill request using the full non-hidden tool catalog and return a draft skill design without persisting changes.")
                .inputType(DesignSkillRequest.class)
                .build();
    }

    /**
     * Restricted write tool that persists a fully-specified skill payload.
     * This tool does not perform natural-language design inference.
     */
    @Bean
    @Restricted
    @ToolCategory("Skills")
    public ToolCallback createSkill(SkillService skillService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("createSkill", (CreateSkillRequest req) -> {
                    if (req == null || req.name() == null || req.name().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"name is required\"}";
                    }
                    if (req.description() == null || req.description().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"description is required\"}";
                    }
                    if (req.groupUuid() == null || req.groupUuid().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"groupUuid is required\"}";
                    }
                    if (req.instructions() == null || req.instructions().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"instructions is required\"}";
                    }

                    try {
                        SkillService.SkillRequest skillRequest = new SkillService.SkillRequest(
                                req.name().trim(),
                                req.description().trim(),
                                req.groupUuid().trim(),
                                req.visibilityEffective(),
                                req.parameters() == null ? List.of() : req.parameters(),
                                req.instructions().trim(),
                                req.allowedTools() == null ? List.of() : req.allowedTools(),
                                req.allowedTypes() == null ? List.of() : req.allowedTypes(),
                                req.subSkillUuids() == null ? List.of() : req.subSkillUuids(),
                                null,
                                null,
                                null,
                                req.secrets() == null ? List.of() : req.secrets(),
                                List.of());

                        Skill created = skillService.create(skillRequest);
                        return objectMapper.writeValueAsString(Map.of(
                                "status", "ok",
                                "skillUuid", created.uuid(),
                                "name", created.name(),
                                "groupUuid", created.groupUuid()));
                    } catch (ToolSuspensionException ex) {
                        throw ex;
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Persist a skill from explicit fields only. Use this after design is complete. No natural-language inference is performed by this tool.")
                .inputType(CreateSkillRequest.class)
                .build();
            return new VisualizableToolCallback(delegate, this::formatCreateSkillAuthorizationDetails);
    }

    // -------------------------------------------------------------------------
    // Existing function-calling tools (unchanged)
    // -------------------------------------------------------------------------

    @Bean
    @ToolCategory("Scheduling")
    public ToolCallback delegateTask(ObjectProvider<AiSchedulerService> aiSchedulerServiceProvider,
                                     DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                     DatabaseRepository<ScheduledJob> jobRepository,
                                     DatabaseRepository<AiSession> aiSessionRepository,
                                     AgentAssignmentService agentAssignmentService,
                                     InToolAuthorizationService inToolAuthorizationService) {
        return FunctionToolCallback
                .builder("delegateTask", (DelegateTaskRequest req) -> {
                    try {
                        if (req == null || req.agentName() == null || req.agentName().isBlank()) {
                            return "{\"status\":\"error\",\"message\":\"agentName is required\"}";
                        }
                        if (req.prompt() == null || req.prompt().isBlank()) {
                            return "{\"status\":\"error\",\"message\":\"prompt is required\"}";
                        }

                        String sessionUuid = resolveSessionUuid();
                        List<String> requestedSessionFiles = normalizeSessionFiles(req.sessionFiles());
                        if (!requestedSessionFiles.isEmpty()
                                && (sessionUuid == null || sessionUuid.isBlank() || "system".equals(sessionUuid))) {
                            return "{\"status\":\"error\",\"message\":\"sessionFiles require a non-system session context\"}";
                        }

                        String username = resolveUsername();
                        AiSession activeSession = null;
                        if ((username == null || username.isBlank())
                                && sessionUuid != null
                                && !sessionUuid.isBlank()
                                && !"system".equals(sessionUuid)) {
                            activeSession = aiSessionRepository.get(sessionUuid);
                            if (activeSession != null && activeSession.username() != null && !activeSession.username().isBlank()) {
                                username = activeSession.username();
                            }
                        } else if (sessionUuid != null && !sessionUuid.isBlank() && !"system".equals(sessionUuid)) {
                            activeSession = aiSessionRepository.get(sessionUuid);
                        }
                        if (username == null || username.isBlank()) {
                            return "{\"status\":\"error\",\"message\":\"Unable to resolve user context for delegateTask\"}";
                        }

                        if (activeSession == null && sessionUuid != null && !sessionUuid.isBlank() && !"system".equals(sessionUuid)) {
                            activeSession = aiSessionRepository.get(sessionUuid);
                        }

                        List<AgentTemplate> matches;
                        try (var stream = agentTemplateRepository.list(0, Integer.MAX_VALUE)) {
                            matches = stream
                                    .filter(t -> req.agentName().equals(t.name()))
                                    .toList();
                        }

                        if (matches.isEmpty()) {
                            return "{\"status\":\"error\",\"message\":\"No agent found with name: "
                                    + req.agentName().replace("\"", "'") + "\"}";
                        }

                        if (matches.size() > 1) {
                            List<Map<String, String>> candidates = matches.stream()
                                    .map(t -> Map.of("uuid", t.uuid(), "name", t.name()))
                                    .toList();
                            return objectMapper.writeValueAsString(Map.of(
                                    "status", "error",
                                    "message", "Ambiguous agentName. Multiple agents found.",
                                    "agentName", req.agentName(),
                                    "candidates", candidates));
                        }

                        AgentTemplate target = matches.get(0);
                        boolean assignedToUser = username != null
                            && !username.isBlank()
                            && !"system".equalsIgnoreCase(username)
                            && agentAssignmentService.isAssignedToUser(target, username);
                        if (!assignedToUser) {
                            String sourceAgentId = activeSession == null ? null : activeSession.activeAgentTemplateId();
                            String sourceAgentName = null;
                            if (sourceAgentId != null && !sourceAgentId.isBlank()) {
                            AgentTemplate sourceAgent = agentTemplateRepository.get(sourceAgentId);
                            sourceAgentName = sourceAgent == null ? null : sourceAgent.name();
                            }
                            String authorizationPayload = objectMapper.writeValueAsString(Map.of(
                                "tool", "delegateTask",
                                "targetAgentName", target.name(),
                                "targetAgentUuid", target.uuid(),
                                "sourceAgentUuid", sourceAgentId == null ? "" : sourceAgentId,
                                "sourceAgentName", sourceAgentName == null ? "" : sourceAgentName,
                                "sourceSessionUuid", sessionUuid == null ? "" : sessionUuid,
                                "requestedBy", username == null ? "" : username));
                            String markdownDetails = "## Delegation Request\n"
                                + "- Source Agent: " + (sourceAgentName == null ? "(unknown)" : sourceAgentName) + "\n"
                                + "- Source Session: " + (sessionUuid == null ? "(none)" : sessionUuid) + "\n"
                                + "- Target Agent: " + target.name() + "\n"
                                + "- Requested By: " + (username == null || username.isBlank() ? "system" : username) + "\n\n"
                                + "Approve this delegation to allow one execution of delegateTask.";
                            inToolAuthorizationService.requireAdminApproval(
                                "delegateTask",
                                authorizationPayload,
                                "Delegation Authorization Required",
                                "Administrator approval is required before this agent delegation can continue.",
                                markdownDetails);
                        }
                        if (target.agentType() != sh.vork.ai.agent.AgentType.BACKGROUND) {
                            return objectMapper.writeValueAsString(Map.of(
                                    "status", "error",
                                    "message", "Agent is not background-capable.",
                                    "agentName", target.name(),
                                    "agentType", target.agentType().name()));
                        }

                        ScheduledJob assignedJob = null;
                        if (req.jobUuid() != null && !req.jobUuid().isBlank()) {
                            assignedJob = jobRepository.get(req.jobUuid().trim());
                            if (assignedJob == null) {
                            return objectMapper.writeValueAsString(Map.of(
                                "status", "error",
                                "message", "Assigned job not found.",
                                "jobUuid", req.jobUuid().trim()));
                            }

                            if (target.jobUuids() == null || !target.jobUuids().contains(assignedJob.id())) {
                            return objectMapper.writeValueAsString(Map.of(
                                "status", "error",
                                "message", "Selected job is not assigned to target agent.",
                                "agentName", target.name(),
                                "jobUuid", assignedJob.id()));
                            }

                            if (assignedJob.userId() != null
                                && !assignedJob.userId().isBlank()
                                && !assignedJob.userId().equals(username)) {
                            return objectMapper.writeValueAsString(Map.of(
                                "status", "error",
                                "message", "Selected job belongs to a different user.",
                                "jobUuid", assignedJob.id()));
                            }

                            if (activeSession != null
                                && activeSession.activeAgentTemplateId() != null
                                && !activeSession.activeAgentTemplateId().isBlank()) {
                            AgentTemplate activeAgent = agentTemplateRepository.get(activeSession.activeAgentTemplateId());
                            if (activeAgent != null && activeAgent.jobUuids() != null
                                && !activeAgent.jobUuids().isEmpty()
                                && !activeAgent.jobUuids().contains(assignedJob.id())) {
                                return objectMapper.writeValueAsString(Map.of(
                                    "status", "error",
                                    "message", "Current active agent is not permitted to delegate this job.",
                                    "jobUuid", assignedJob.id(),
                                    "activeAgentUuid", activeAgent.uuid()));
                            }
                            }
                        }

                        RecommendedModelSelection recommendedModel = parseRecommendedModel(target.recommendedModel());

                        String jobName = "Dynamic: " + target.name();
                        String dynamicArtifactId = "delegated" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        String dynamicJobId = "dynamic-" + dynamicArtifactId;
                        ScheduledJob dynamicJob = new ScheduledJob(
                            dynamicJobId,
                                jobName,
                                req.prompt().trim(),
                            null,
                                username,
                            InvocationType.DYNAMIC,
                                java.time.Instant.now(),
                                0L,
                            assignedJob != null && assignedJob.durationType() != null
                                ? assignedJob.durationType()
                                : DurationType.MINUTES,
                                0L,
                                0L,
                                target.uuid(),
                            assignedJob != null
                                ? assignedJob.provider()
                                : recommendedModel.provider(),
                            assignedJob != null
                                ? assignedJob.modelId()
                                : recommendedModel.modelId(),
                            assignedJob != null && assignedJob.oobTimeoutMinutes() > 0 ? assignedJob.oobTimeoutMinutes() : 240,
                            assignedJob != null ? assignedJob.expectedOutput() : null,
                                ScheduledJobStatus.WAITING,
                            assignedJob != null
                                ? assignedJob.skillUuids()
                                : target.skillUuids(),
                            assignedJob != null
                                ? assignedJob.toolIds()
                                : target.allowedTools(),
                                List.of(username),
                            "dynamic",
                                dynamicArtifactId,
                            "DYNAMIC",
                                ArtifactStatus.SNAPSHOT);

                            AiSchedulerService aiSchedulerService = aiSchedulerServiceProvider.getIfAvailable();
                            if (aiSchedulerService == null) {
                                return "{\"status\":\"error\",\"message\":\"Scheduler service is unavailable\"}";
                            }

                        ScheduledJob saved = aiSchedulerService.scheduleDelegatedDynamicJob(
                            dynamicJob,
                            (sessionUuid == null || sessionUuid.isBlank() || "system".equals(sessionUuid))
                                ? null
                                : sessionUuid,
                            requestedSessionFiles);
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "scheduled");
                        response.put("jobId", saved.id());
                        response.put("jobName", saved.name());
                        response.put("agentName", target.name());
                        response.put("agentUuid", target.uuid());
                        response.put("invocationType", saved.invocationType().name());
                        if (!requestedSessionFiles.isEmpty()) {
                            response.put("copiedSessionFileCount", requestedSessionFiles.size());
                        }
                        if (assignedJob != null) {
                            response.put("sourceJobUuid", assignedJob.id());
                        }
                        return objectMapper.writeValueAsString(response);
                    } catch (ToolSuspensionException ex) {
                        throw ex;
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Delegate work to another agent using a dynamic one-off background run. If jobUuid is provided, its policy/tooling settings are inherited and validated against the selected agent; if omitted, the run is created directly from the target background agent plus the provided prompt.")
                .inputType(DelegateTaskRequest.class)
                .build();
    }

    private static RecommendedModelSelection parseRecommendedModel(String recommendedModel) {
        if (recommendedModel == null || recommendedModel.isBlank()) {
            return new RecommendedModelSelection(AiProvider.BACKGROUND_SCHEDULER.name(), null);
        }
        String normalized = recommendedModel.trim();
        int idx = normalized.indexOf(':');
        if (idx < 0) {
            try {
                return new RecommendedModelSelection(AiProvider.valueOf(normalized.toUpperCase(Locale.ROOT)).name(), null);
            } catch (IllegalArgumentException ignored) {
                return new RecommendedModelSelection(AiProvider.BACKGROUND_SCHEDULER.name(), normalized);
            }
        }

        String providerRaw = normalized.substring(0, idx).trim().toUpperCase(Locale.ROOT);
        String modelId = normalized.substring(idx + 1).trim();
        String providerName;
        try {
            providerName = AiProvider.valueOf(providerRaw).name();
        } catch (IllegalArgumentException ignored) {
            providerName = AiProvider.BACKGROUND_SCHEDULER.name();
        }
        return new RecommendedModelSelection(providerName, modelId.isBlank() ? null : modelId);
    }

    private record RecommendedModelSelection(String provider, String modelId) {}

    private static List<String> normalizeSessionFiles(List<String> sessionFiles) {
        if (sessionFiles == null || sessionFiles.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : sessionFiles) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }

    @Bean
    @Hidden
    @ToolCategory("Scheduling")
    public ToolCallback completeBackgroundTask(DatabaseRepository<AiSession> aiSessionRepository,
                                               DatabaseRepository<JobResult> jobResultRepository,
                                               BackgroundExecutionContext backgroundExecutionContext) {
        return FunctionToolCallback
                .builder("completeBackgroundTask", (CompleteBackgroundTaskRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if ((sessionUuid == null || sessionUuid.isBlank() || "system".equals(sessionUuid))
                            && req != null && req.sessionUuid() != null && !req.sessionUuid().isBlank()) {
                        sessionUuid = req.sessionUuid().trim();
                    }

                    if (sessionUuid == null || sessionUuid.isBlank() || "system".equals(sessionUuid)) {
                        return "{\"error\":\"This tool is only available for out-of-band background tasks.\"}";
                    }

                    AiSession session = aiSessionRepository.get(sessionUuid);
                    if (session == null || session.originMode() != SessionOriginMode.BACKGROUND) {
                        return "{\"error\":\"This tool is only available for out-of-band background tasks.\"}";
                    }

                    // Persist the job result before marking the session complete
                    String jobId = session.environmentVariables() != null
                            ? session.environmentVariables().get("JOB_ID")
                            : null;
                    if (jobId != null && !jobId.isBlank()) {
                        JobResult result = new JobResult(
                                java.util.UUID.randomUUID().toString(),
                                jobId,
                                sessionUuid,
                                req.success(),
                                req.report(),
                                System.currentTimeMillis());
                        jobResultRepository.save(result);
                    }

                    aiSessionRepository.save(new AiSession(
                            session.uuid(),
                            session.provider(),
                            session.originMode(),
                            session.username(),
                            session.name(),
                            session.createdAt(),
                            session.currentRoundCount(),
                            session.messages(),
                            mergedEnv(session.environmentVariables(), req.report()),
                            AiSessionStatus.COMPLETED,
                            session.activeAgentTemplateId(),
                            session.modelId(),
                            session.skillStack(),
                            session.sessionSkillUuids(),
                            session.sessionToolIds()));

                    backgroundExecutionContext.markExecutionComplete();
                    return "{\"status\":\"shutdown_initiated\"}";
                })
                .description("Signals that the background task has entirely fulfilled its operational objectives and that the background processing loop should now gracefully terminate. You MUST supply a boolean 'success' value and a 'report' string summarising what was done and produced.")
                .inputType(CompleteBackgroundTaskRequest.class)
                .build();
    }

    private static Map<String, String> mergedEnv(Map<String, String> env, String report) {
        if (report == null || report.isBlank()) {
            return env;
        }
        Map<String, String> merged = new HashMap<>();
        if (env != null && !env.isEmpty()) {
            merged.putAll(env);
        }
        merged.put("JOB_COMPLETION_REPORT", report);
        return Map.copyOf(merged);
    }

    /**
     * Hidden tool available only to sessions with an active skill frame on their stack.
     * The skill AI calls this once when its objective is fully met.  It pops the
     * top {@link sh.vork.skill.SkillFrame} from the session's {@code skillStack},
     * persists the output into {@link sh.vork.ai.ToolExecutionContext} so the
     * parent loop can retrieve it, and returns normally so Spring AI calls the
     * model one final time (which should emit FINISHED_TURN per the skill protocol).
     */
    @Bean("completeSkillExecution")
    @Hidden
    @ToolCategory("Skills")
    public ToolCallback completeSkillExecution(DatabaseRepository<AiSession> aiSessionRepository) {
        return FunctionToolCallback
                .builder("completeSkillExecution", (CompleteSkillExecutionRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"error\":\"This tool is only available inside a skill session.\"}";
                    }
                    AiSession session = aiSessionRepository.get(sessionUuid);
                    if (session == null || session.skillStack() == null || session.skillStack().isEmpty()) {
                        return "{\"error\":\"This tool is only available inside a skill session.\"}";
                    }

                    // Store output so executeSkillSubLoop can retrieve it after this turn ends
                    String output = req.output() != null ? req.output() : "";
                    sh.vork.ai.context.ToolExecutionContext.put("__skill_output__", output);

                    // Pop the top skill frame from the stack and save
                    java.util.List<sh.vork.skill.SkillFrame> newStack =
                            session.skillStack().subList(0, session.skillStack().size() - 1);
                    aiSessionRepository.save(new AiSession(
                            session.uuid(),
                            session.provider(),
                            session.originMode(),
                            session.username(),
                            session.name(),
                            session.createdAt(),
                            session.currentRoundCount(),
                            session.messages(),
                            session.environmentVariables(),
                            AiSessionStatus.RUNNING,
                            session.activeAgentTemplateId(),
                            session.modelId(),
                            java.util.List.copyOf(newStack),
                            session.sessionSkillUuids(),
                            session.sessionToolIds()));

                    log.debug("Skill frame popped [session={}, remainingFrames={}]",
                            sessionUuid, newStack.size());
                    String escapedOutput = output
                            .replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                    return "{\"status\":\"skill_complete\",\"output\":\"" + escapedOutput + "\"}";
                })
                .description("Signals that the skill has fully completed its objective. Call this exactly once with the skill output when all required work is done.")
                .inputType(CompleteSkillExecutionRequest.class)
                .build();
    }

    /**
     * Mandatory hidden meta-tool available to the AI in every turn, including inside
     * skill frames.  Call this to express reasoning or analysis before invoking the
     * next action tool — it MUST NOT be used as a substitute for taking action.
     *
     * <p>The reasoning string is:
     * <ul>
     *   <li>logged at DEBUG level with the session UUID;</li>
     *   <li>broadcast as an {@code AI_THINKING} WebSocket event to the session topic
     *       so interactive UIs can render it (background sessions have no subscriber,
     *       so the send is silently a no-op).</li>
     * </ul>
     * Reasoning is intentionally <em>not</em> added to the conversation history so
     * it does not inflate the context window.
     */
    @Bean("think")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback think(SimpMessagingTemplate messaging) {
        return FunctionToolCallback
                .builder("think", (ThinkRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    String reasoning = req.reasoning() != null ? req.reasoning() : "";
                    log.debug("AI thinking [session={}]: {}", sessionUuid, reasoning);
                    if (sessionUuid != null && !sessionUuid.isBlank()) {
                        messaging.convertAndSend(
                                "/topic/chat/" + sessionUuid,
                                new UiEventFrame(
                                        java.util.UUID.randomUUID().toString(),
                                        "AI_THINKING",
                                        "THINKING",
                                        reasoning,
                                        null));
                    }
                    return "{\"status\":\"ok\",\"hint\":\"Reasoning logged. Invoke your next tool now.\"}";
                })
                .description("Log your reasoning or analysis mid-turn without ending the turn. "
                        + "Call this to express your thinking, then IMMEDIATELY invoke the next action tool. "
                        + "NEVER end a turn with only a think call.")
                .inputType(ThinkRequest.class)
                .build();
    }

    /**
     * Global meta-tool for persisting turn-to-turn checkpoints in session memory.
     *
     * <p>Entries are stored under indexed environment keys so they are injected
     * into subsequent system prompts via {@link sh.vork.ai.service.AiOrchestrationService}.
     */
    @Bean("recordProgress")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback recordProgress(SessionEnvironmentService sessionEnvironmentService,
                                       SimpMessagingTemplate messaging) {
        return FunctionToolCallback
                .builder("recordProgress", (RecordProgressRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"No session bound\"}";
                    }

                    String entry = req.entry() == null ? "" : req.entry().trim();
                    if (entry.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"entry is required\"}";
                    }

                    Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
                    int nextIndex = 1;
                    if (env != null) {
                        String prior = env.get("BG_PROGRESS_COUNT");
                        if (prior != null) {
                            try {
                                nextIndex = Math.max(1, Integer.parseInt(prior) + 1);
                            } catch (NumberFormatException ignored) {
                                nextIndex = 1;
                            }
                        }
                    }

                    String key = String.format("BG_PROGRESS_%04d", nextIndex);
                    sessionEnvironmentService.setEnv(sessionUuid, "BG_PROGRESS_COUNT", Integer.toString(nextIndex));
                    sessionEnvironmentService.setEnv(sessionUuid, key, entry);

                    sh.vork.ai.context.ToolExecutionContext.put("BG_PROGRESS_COUNT", Integer.toString(nextIndex));
                    sh.vork.ai.context.ToolExecutionContext.put(key, entry);

                    log.debug("AI progress recorded [session={}, key={}, entry={}]", sessionUuid, key, entry);
                    messaging.convertAndSend(
                            "/topic/chat/" + sessionUuid,
                            new UiEventFrame(
                                    java.util.UUID.randomUUID().toString(),
                                    "AI_PROGRESS",
                                    "PROGRESS",
                                    entry,
                                    null));

                    return "{\"status\":\"ok\",\"storedKey\":\"" + key + "\"}";
                })
                .description("Persist a concise progress checkpoint to session memory for use in later turns. "
                        + "Use this after completing significant steps (e.g. host scanned, report generated, report sent).")
                .inputType(RecordProgressRequest.class)
                .build();
    }

    @Bean("toggleInputRelay")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback toggleInputRelay(SessionEnvironmentService sessionEnvironmentService) {
        return FunctionToolCallback
                .builder("toggleInputRelay", (ToggleInputRelayRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"No session bound\"}";
                    }

                    String value = Boolean.toString(req.enabled());
                    sessionEnvironmentService.setEnv(sessionUuid, "WEB_INPUT_RELAY_ENABLED", value);
                    ToolExecutionContext.put("WEB_INPUT_RELAY_ENABLED", value);
                    log.info("Web relay input toggled [session={}, enabled={}]", sessionUuid, req.enabled());
                    return "{\"status\":\"ok\",\"enabled\":" + req.enabled() + "}";
                })
                .description("Enable or disable secure relay input handling for this web chat session.")
                .inputType(ToggleInputRelayRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("Meta")
    public ToolCallback configureSessionApproval(SessionEnvironmentService sessionEnvironmentService,
                                                 ChannelService channelService,
                                                 ApprovalPolicyService approvalPolicyService) {
        return FunctionToolCallback
                .builder("configureSessionApproval", (ConfigureSessionApprovalRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank() || "system".equalsIgnoreCase(sessionUuid)) {
                        return "{\"status\":\"error\",\"message\":\"No AI session bound\"}";
                    }

                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    boolean canManageUsers = auth != null
                            && auth.getAuthorities() != null
                            && auth.getAuthorities().stream().anyMatch(a -> "USERS_MANAGE".equals(a.getAuthority()));
                    if (!canManageUsers) {
                        return "{\"status\":\"error\",\"message\":\"configureSessionApproval requires USERS_MANAGE\"}";
                    }

                    if (req != null && Boolean.TRUE.equals(req.clear())) {
                        sessionEnvironmentService.deleteEnv(sessionUuid, ApprovalPolicyRuntimeResolver.SESSION_APPROVAL_OVERRIDE_ENV);
                        ToolExecutionContext.remove(ApprovalPolicyRuntimeResolver.SESSION_APPROVAL_OVERRIDE_ENV);
                        return "{\"status\":\"ok\",\"action\":\"cleared\"}";
                    }

                    boolean enabled = req == null || req.enabled() == null || req.enabled();
                    String policyId = req == null || req.policyId() == null ? "" : req.policyId().trim();
                    String policyName = req == null || req.policyName() == null ? "" : req.policyName().trim();
                    String reason = req == null || req.reason() == null ? "" : req.reason().trim();
                    String responsePolicyRaw = req == null || req.responsePolicy() == null ? "" : req.responsePolicy().trim();
                    Integer quorum = req == null ? null : req.quorum();

                    if (!policyId.isBlank() && approvalPolicyService.getPolicy(policyId) == null) {
                        return "{\"status\":\"error\",\"message\":\"Unknown policyId: "
                                + policyId.replace("\"", "'") + "\"}";
                    }
                    if (!policyName.isBlank()) {
                        ApprovalPolicy policyByName;
                        try {
                            policyByName = approvalPolicyService.getPolicyByName(policyName);
                        } catch (IllegalArgumentException ex) {
                            return "{\"status\":\"error\",\"message\":\""
                                    + ex.getMessage().replace("\"", "'") + "\"}";
                        }
                        if (policyByName == null) {
                            return "{\"status\":\"error\",\"message\":\"Unknown policyName: "
                                    + policyName.replace("\"", "'") + "\"}";
                        }
                        if (!policyId.isBlank() && !policyId.equals(policyByName.uuid())) {
                            return "{\"status\":\"error\",\"message\":\"policyId and policyName refer to different policies\"}";
                        }
                        policyId = policyByName.uuid();
                    }

                    List<String> channels = new ArrayList<>();
                    if (req != null && req.channelNames() != null) {
                        for (String raw : req.channelNames()) {
                            if (raw == null || raw.isBlank()) {
                                continue;
                            }
                            String query = raw.trim();
                            var exact = channelService.resolveByChannelName(query);
                            if (exact.isPresent()) {
                                channels.add(exact.get().channelName());
                                continue;
                            }
                            List<ChannelRef> candidates = channelService.search(query, 8);
                            if (candidates.isEmpty()) {
                                return "{\"status\":\"error\",\"message\":\"Unknown channel: "
                                        + query.replace("\"", "'") + "\"}";
                            }
                            if (candidates.size() > 1) {
                                return "{\"status\":\"error\",\"message\":\"Ambiguous channel query: "
                                        + query.replace("\"", "'") + ". Use exact channelName.\"}";
                            }
                            channels.add(candidates.getFirst().channelName());
                        }
                    }
                    channels = channels.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();

                    if (policyId.isBlank() && enabled && channels.isEmpty()) {
                        return "{\"status\":\"error\",\"message\":\"Provide policyId or at least one channel when enabled=true\"}";
                    }

                    String responsePolicy = responsePolicyRaw.isBlank() ? "FIRST" : responsePolicyRaw.toUpperCase(Locale.ROOT);
                    if (!Set.of("FIRST", "ALL", "QUORUM").contains(responsePolicy)) {
                        return "{\"status\":\"error\",\"message\":\"responsePolicy must be FIRST, ALL, or QUORUM\"}";
                    }
                    if (!"QUORUM".equals(responsePolicy)) {
                        quorum = null;
                    } else if (quorum == null || quorum < 1) {
                        quorum = 1;
                    }

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("enabled", enabled);
                    if (!policyId.isBlank()) {
                        payload.put("policyId", policyId);
                    }
                    if (!channels.isEmpty()) {
                        payload.put("channels", channels);
                    }
                    payload.put("responsePolicy", responsePolicy);
                    if (quorum != null) {
                        payload.put("quorum", quorum);
                    }
                    if (!reason.isBlank()) {
                        payload.put("reason", reason);
                    }

                    try {
                        String json = objectMapper.writeValueAsString(payload);
                        sessionEnvironmentService.setEnv(sessionUuid, ApprovalPolicyRuntimeResolver.SESSION_APPROVAL_OVERRIDE_ENV, json);
                        ToolExecutionContext.put(ApprovalPolicyRuntimeResolver.SESSION_APPROVAL_OVERRIDE_ENV, json);

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "ok");
                        response.put("sessionUuid", sessionUuid);
                        response.put("override", payload);
                        log.info("Session approval override configured [session={}, enabled={}, policyId={}, channels={}, responsePolicy={}]",
                                sessionUuid, enabled, policyId, channels.size(), responsePolicy);
                        return objectMapper.writeValueAsString(response);
                    } catch (Exception ex) {
                        return "{\"status\":\"error\",\"message\":\""
                                + (ex.getMessage() == null ? "serialization failed" : ex.getMessage().replace("\"", "'"))
                                + "\"}";
                    }
                })
                .description("Configure or clear a session-scoped approval override for protected tool calls."
                        + " Admin-only: requires USERS_MANAGE."
                        + " Use clear=true to remove override."
                    + " When enabled, provide either policyId, policyName, or channelNames."
                        + " responsePolicy supports FIRST, ALL, or QUORUM.")
                .inputType(ConfigureSessionApprovalRequest.class)
                .build();
    }

    /**
     * Global meta-tool for generic key/value session memory.
     *
     * <p>Values are persisted to session environment variables and are injected
     * into subsequent system prompts.
     */
    @Bean("memory")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback memory(SessionEnvironmentService sessionEnvironmentService) {
        return FunctionToolCallback
                .builder("memory", (MemoryRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"No session bound\"}";
                    }

                    String operation = req.operation() == null ? "set" : req.operation().trim().toLowerCase(Locale.ROOT);
                    Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);

                    switch (operation) {
                        case "set" -> {
                            String key = req.key() == null ? "" : req.key().trim();
                            if (key.isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"key is required for set\"}";
                            }
                            String value = req.value() == null ? "" : req.value();
                            sessionEnvironmentService.setEnv(sessionUuid, key, value);
                            ToolExecutionContext.put(key, value);
                            log.debug("AI memory set [session={}, key={}, value={}]", sessionUuid, key, value);
                            return "{\"status\":\"ok\",\"operation\":\"set\",\"key\":\"" + key + "\"}";
                        }
                        case "get" -> {
                            String key = req.key() == null ? "" : req.key().trim();
                            if (key.isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"key is required for get\"}";
                            }
                            String value = env.get(key);
                            try {
                                return objectMapper.writeValueAsString(Map.of(
                                        "status", "ok",
                                        "operation", "get",
                                        "key", key,
                                        "value", value == null ? "" : value,
                                        "found", value != null));
                            } catch (Exception e) {
                                log.warn("memory get serialization failed [session={}, key={}]", sessionUuid, key, e);
                                return "{\"status\":\"error\",\"message\":\"Unable to serialize memory response\"}";
                            }
                        }
                        case "list" -> {
                            String prefix = req.prefix() == null ? "" : req.prefix();
                            Map<String, String> filtered = new java.util.TreeMap<>();
                            if (env != null) {
                                env.forEach((k, v) -> {
                                    if (prefix.isBlank() || k.startsWith(prefix)) {
                                        filtered.put(k, v);
                                    }
                                });
                            }
                            try {
                                return objectMapper.writeValueAsString(Map.of(
                                        "status", "ok",
                                        "operation", "list",
                                        "prefix", prefix,
                                        "count", filtered.size(),
                                        "entries", filtered));
                            } catch (Exception e) {
                                log.warn("memory list serialization failed [session={}, prefix={}]", sessionUuid, prefix, e);
                                return "{\"status\":\"error\",\"message\":\"Unable to serialize memory response\"}";
                            }
                        }
                        case "delete" -> {
                            String key = req.key() == null ? "" : req.key().trim();
                            if (key.isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"key is required for delete\"}";
                            }
                            boolean existed = env != null && env.containsKey(key);
                            sessionEnvironmentService.deleteEnv(sessionUuid, key);
                            log.debug("AI memory delete [session={}, key={}, existed={}]", sessionUuid, key, existed);
                            return "{\"status\":\"ok\",\"operation\":\"delete\",\"key\":\""
                                    + key + "\",\"deleted\":" + existed + "}";
                        }
                        default -> {
                            return "{\"status\":\"error\",\"message\":\"Unsupported operation. Use set|get|list|delete\"}";
                        }
                    }
                })
                .description("Session key/value memory store. Use operation=set|get|list|delete to manage reusable context that is injected into future system prompts.")
                .inputType(MemoryRequest.class)
                .build();
    }

    /**
     * Hidden meta-tool that returns the current local system date and time.
     * Available in every AI turn to support time-aware planning and responses.
     */
    @Bean("getDateTime")
    @Hidden
    @ToolCategory("Meta")
    public ToolCallback getDateTime() {
        return FunctionToolCallback
                .builder("getDateTime", (GetDateTimeRequest req) -> {
                    java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
                    return "{\"status\":\"ok\","
                            + "\"isoDateTime\":\"" + now.format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME) + "\"," 
                            + "\"localDate\":\"" + now.toLocalDate() + "\"," 
                            + "\"localTime\":\"" + now.toLocalTime().withNano(0) + "\"," 
                            + "\"zoneId\":\"" + now.getZone().getId() + "\"}";
                })
                .description("Return the current local system date and time, including timezone.")
                .inputType(GetDateTimeRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Encoding & Crypto")
    public ToolCallback base64EncodeString() {
        return FunctionToolCallback
                .builder("base64EncodeString", (Base64EncodeStringRequest req) -> {
                    if (req == null || req.input() == null) {
                        throw new IllegalArgumentException("input is required");
                    }
                    return Base64.getEncoder().encodeToString(req.input().getBytes(StandardCharsets.UTF_8));
                })
                .description("Encode a UTF-8 string as Base64 text.")
                .inputType(Base64EncodeStringRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Encoding & Crypto")
    public ToolCallback base64DecodeString() {
        return FunctionToolCallback
                .builder("base64DecodeString", (Base64DecodeStringRequest req) -> {
                    if (req == null || req.input() == null || req.input().isBlank()) {
                        throw new IllegalArgumentException("input is required");
                    }
                    byte[] decoded = Base64.getDecoder().decode(req.input().trim());
                    return new String(decoded, StandardCharsets.UTF_8);
                })
                .description("Decode Base64 text to a UTF-8 string.")
                .inputType(Base64DecodeStringRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("Encoding & Crypto")
    public ToolCallback configureEncryption(SessionEnvironmentService sessionEnvironmentService,
                                            SessionFileSystem sessionFileSystem) {
        return FunctionToolCallback
                .builder("configureEncryption", (ConfigureEncryptionRequest req) -> {
                    String sessionUuid = resolveSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank() || "system".equalsIgnoreCase(sessionUuid)) {
                        throw new IllegalArgumentException("configureEncryption requires a bound AI session");
                    }

                    if (req != null && Boolean.TRUE.equals(req.clear())) {
                        sessionEnvironmentService.deleteEnv(sessionUuid, "SESSION_ENCRYPTION_CONFIG");
                        ToolExecutionContext.remove("SESSION_ENCRYPTION_CONFIG");
                        return "{\"status\":\"ok\",\"cleared\":true}";
                    }

                    if (req == null || req.type() == null || req.type().isBlank()) {
                        throw new IllegalArgumentException("type is required (RSA or SOFTWARE)");
                    }
                    if (req.filePath() == null || req.filePath().isBlank()) {
                        throw new IllegalArgumentException("filePath is required");
                    }

                    String type = req.type().trim().toUpperCase(Locale.ROOT);
                    if (!"RSA".equals(type) && !"SOFTWARE".equals(type)) {
                        throw new IllegalArgumentException("type must be RSA or SOFTWARE");
                    }

                    Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
                    String resolvedFilePath = resolveSessionPathTemplate(req.filePath().trim(), env);
                    ensureSessionFileExists(sessionFileSystem, sessionUuid, resolvedFilePath);

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", type);
                    payload.put("filePath", resolvedFilePath);

                    if ("SOFTWARE".equals(type)) {
                        if (req.keystoreAlias() != null && !req.keystoreAlias().isBlank()) {
                            payload.put("keystoreAlias", req.keystoreAlias().trim());
                        }
                        if (req.keystorePassword() != null && !req.keystorePassword().isBlank()) {
                            payload.put("keystorePassword", req.keystorePassword());
                        }
                    }

                    try {
                        String json = objectMapper.writeValueAsString(payload);
                        sessionEnvironmentService.setEnv(sessionUuid, "SESSION_ENCRYPTION_CONFIG", json);
                        ToolExecutionContext.put("SESSION_ENCRYPTION_CONFIG", json);

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "ok");
                        response.put("sessionUuid", sessionUuid);
                        response.put("configuration", payload);
                        return objectMapper.writeValueAsString(response);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Failed to persist session encryption configuration", ex);
                    }
                })
                .description("Configure session encryption mode for encryptString/decryptString. "
                        + "Use type=RSA with a PKCS#8 private key file, or type=SOFTWARE with a .p12 keystore file. "
                        + "For SOFTWARE, keystoreAlias and keystorePassword are optional and defaults are used when omitted. "
                        + "Use clear=true to revert to system default encryption.")
                .inputType(ConfigureEncryptionRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("Encoding & Crypto")
    public ToolCallback encryptString(EncryptionService encryptionService,
                                      SessionFileSystem sessionFileSystem,
                                      SessionEnvironmentService sessionEnvironmentService) {
        return FunctionToolCallback
                .builder("encryptString", (EncryptStringRequest req) -> {
                    if (req == null || req.input() == null || req.input().isBlank()) {
                        throw new IllegalArgumentException("input is required");
                    }

                    String sessionUuid = resolveSessionUuid();
                    SessionEncryptionConfig config = resolveSessionEncryptionConfig(sessionEnvironmentService, sessionUuid);
                    if (config == null) {
                        return encryptionService.encrypt(req.input());
                    }

                    byte[] keyMaterial = readSessionFileBytes(sessionFileSystem, sessionUuid, config.filePath());
                    if ("RSA".equals(config.type())) {
                        return encryptionService.encryptWithLegacyPrivateKey(req.input(), keyMaterial);
                    }
                    return encryptionService.encryptWithSoftwareKeystore(
                            req.input(),
                            keyMaterial,
                            config.keystoreAlias(),
                            config.keystorePassword());
                })
                .description("Encrypt a UTF-8 string using session encryption config if present; otherwise system default encryption is used.")
                .inputType(EncryptStringRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("Encoding & Crypto")
    public ToolCallback decryptString(EncryptionService encryptionService,
                                      SessionFileSystem sessionFileSystem,
                                      SessionEnvironmentService sessionEnvironmentService) {
        return FunctionToolCallback
                .builder("decryptString", (DecryptStringRequest req) -> {
                    if (req == null || req.input() == null || req.input().isBlank()) {
                        throw new IllegalArgumentException("input is required");
                    }

                    String sessionUuid = resolveSessionUuid();
                    SessionEncryptionConfig config = resolveSessionEncryptionConfig(sessionEnvironmentService, sessionUuid);
                    if (config == null) {
                        return encryptionService.decrypt(req.input());
                    }

                    byte[] keyMaterial = readSessionFileBytes(sessionFileSystem, sessionUuid, config.filePath());
                    if ("RSA".equals(config.type())) {
                        return encryptionService.decryptWithLegacyPrivateKey(req.input(), keyMaterial);
                    }
                    return encryptionService.decryptWithSoftwareKeystore(
                            req.input(),
                            keyMaterial,
                            config.keystoreAlias(),
                            config.keystorePassword());
                })
                .description("Decrypt text using session encryption config if present; otherwise system default encryption is used.")
                .inputType(DecryptStringRequest.class)
                .build();
    }

    private SessionEncryptionConfig resolveSessionEncryptionConfig(SessionEnvironmentService sessionEnvironmentService,
                                                                   String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank() || "system".equalsIgnoreCase(sessionUuid)) {
            return null;
        }

        Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
        if (env == null || env.isEmpty()) {
            return null;
        }

        String raw = env.get("SESSION_ENCRYPTION_CONFIG");
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            var node = objectMapper.readTree(raw);
            String type = node.path("type").asText("").trim().toUpperCase(Locale.ROOT);
            String filePath = node.path("filePath").asText("").trim();
            if (type.isBlank() || filePath.isBlank()) {
                return null;
            }

            String keystoreAlias = node.has("keystoreAlias") ? node.path("keystoreAlias").asText("").trim() : "";
            String keystorePassword = node.has("keystorePassword") ? node.path("keystorePassword").asText("") : "";
            return new SessionEncryptionConfig(
                    type,
                    filePath,
                    keystoreAlias.isBlank() ? null : keystoreAlias,
                    keystorePassword.isBlank() ? null : keystorePassword);
        } catch (Exception ex) {
            throw new IllegalArgumentException("SESSION_ENCRYPTION_CONFIG is invalid JSON");
        }
    }

    private static void ensureSessionFileExists(SessionFileSystem sessionFileSystem,
                                                String sessionUuid,
                                                String relativePath) {
        try (java.io.InputStream in = sessionFileSystem.read(FileArea.SESSION, sessionUuid, relativePath)) {
            in.readNBytes(1);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Session file not found: " + relativePath);
        }
    }

    private static byte[] readSessionFileBytes(SessionFileSystem sessionFileSystem,
                                               String sessionUuid,
                                               String relativePath) {
        if (sessionUuid == null || sessionUuid.isBlank() || "system".equalsIgnoreCase(sessionUuid)) {
            throw new IllegalArgumentException("Encryption file access requires a bound AI session");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Configured encryption filePath is empty");
        }

        try (java.io.InputStream in = sessionFileSystem.read(FileArea.SESSION, sessionUuid, relativePath)) {
            return in.readAllBytes();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to read encryption file from session sandbox: " + relativePath);
        }
    }

    private static String resolveSessionPathTemplate(String rawPath, Map<String, String> env) {
        if (rawPath == null || rawPath.isBlank() || env == null || env.isEmpty()) {
            return rawPath;
        }

        String resolved = rawPath;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            resolved = resolved.replace("${" + key + "}", value);
            resolved = resolved.replace("{{" + key + "}}", value);
        }
        return resolved;
    }

    private record SessionEncryptionConfig(String type,
                                           String filePath,
                                           String keystoreAlias,
                                           String keystorePassword) {}

    @Bean
    @Restricted
    @ToolCategory("Encoding & Crypto")
    public ToolCallback generatePrivateKey(SecureCredentialStore secureCredentialStore) {
        ToolCallback delegate = FunctionToolCallback
                .builder("generatePrivateKey", (GeneratePrivateKeyRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        throw new IllegalArgumentException("Authenticated user is required");
                    }

                    Object secretNameCtx = ToolExecutionContext.get("generate-private-key-secret-name");
                    if (secretNameCtx == null || secretNameCtx.toString().isBlank()) {
                        String keyAlgorithm = req != null && req.keyAlgorithm() != null && !req.keyAlgorithm().isBlank()
                                ? req.keyAlgorithm()
                                : "RSA";

                        List<FormField> fields = List.of(
                                new FormField(
                                        "generate-private-key-secret-name",
                                        "TEXT",
                                        "Secret Name",
                                        "",
                                        "A unique identifier for this key pair (e.g., 'my_app_signing_key'). "
                                                + "The private key will be stored under this name in your secrets.",
                                        true,
                                        FieldSource.CONTEXT,
                                        null
                                )
                        );

                        InteractionFormSchema schema = new InteractionFormSchema(
                                "GENERATE_PRIVATE_KEY_INPUT",
                                "Generate Private Key",
                                "Provide a unique name for the key pair.",
                                fields,
                                List.of(new FormAction("ONCE", "Generate", "primary"))
                        );

                        throw new ToolSuspensionException(
                                "generatePrivateKey",
                                "{}",
                                "Please provide a unique name for the key pair (" + keyAlgorithm + ").",
                                schema);
                    }

                    String secretName = secretNameCtx.toString().trim();
                    String keyAlgorithm = req == null || req.keyAlgorithm() == null || req.keyAlgorithm().isBlank()
                            ? "RSA"
                            : normalizeKeyAlgorithm(req.keyAlgorithm());

                    try {
                        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(keyAlgorithm);
                        Integer keySize = null;
                        if ("RSA".equals(keyAlgorithm)) {
                            keySize = req == null || req.keySize() == null ? 2048 : req.keySize();
                            if (keySize < 2048) {
                                throw new IllegalArgumentException("keySize must be >= 2048 for RSA");
                            }
                            keyPairGenerator.initialize(keySize);
                        }
                        KeyPair keyPair = keyPairGenerator.generateKeyPair();

                        String privateKeyPem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
                        String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

                        // Normalize secret names to uppercase with spaces converted to underscores.
                        // Only A-Z, 0-9 and underscore are allowed after normalization.
                        String normalizedSecretName = normalizeGeneratedSecretBaseName(secretName);
                        String privateKeySecretName = normalizedSecretName + "_PRIVATE";
                        String publicKeySecretName = normalizedSecretName + "_PUBLIC";

                        secureCredentialStore.saveSecretForUser(username, privateKeySecretName, privateKeyPem);
                        secureCredentialStore.saveSecretForUser(username, publicKeySecretName, publicKeyBase64);

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "ok");
                        response.put("secretName", normalizedSecretName);
                        response.put("privateKeySecretRef", "{{" + privateKeySecretName + "}}");
                        response.put("publicKeyLookupRef", "{{" + publicKeySecretName + "}}");
                        response.put("keyAlgorithm", keyAlgorithm);
                        if (keySize != null) {
                            response.put("keySize", keySize);
                        }
                        response.put("suggestedSigningAlgorithm", suggestedSigningAlgorithmForKeyAlgorithm(keyAlgorithm));
                        response.put("nextStep", "Call getPublicKey with secretName to retrieve the Base64 public key.");
                        return objectMapper.writeValueAsString(response);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("Unable to generate and store keypair: " + ex.getMessage(), ex);
                    } finally {
                        ToolExecutionContext.remove("generate-private-key-secret-name");
                    }
                })
                .description(
                    "Generate a new private/public keypair for RSA or Ed25519. The PRIVATE key is stored in encrypted user secrets under secretName and is NOT returned."
                        + " This tool returns only references. Pass privateKeySecretRef to signData.privateKey. Use getPublicKey with the same secretName to fetch the Base64 X.509 public key.")
                .inputType(GeneratePrivateKeyRequest.class)
                .build();

        return new VisualizableToolCallback(delegate, argumentsJson -> {
            try {
                var node = objectMapper.readTree(argumentsJson);
                String keyAlgorithm = node.path("keyAlgorithm").asText("RSA");
                Integer keySize = node.path("keySize").asInt(0);
                
                StringBuilder details = new StringBuilder("Generate private/public key pair\n");
                details.append("- Key Type: ").append(keyAlgorithm).append("\n");
                
                if ("RSA".equalsIgnoreCase(keyAlgorithm) && keySize > 0) {
                    details.append("- Key Size: ").append(keySize).append(" bits\n");
                } else if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
                    details.append("- Key Size: 2048 bits (default)\n");
                }
                
                return details.toString();
            } catch (Exception ex) {
                return "Generate private/public key pair";
            }
        });
    }

    @Bean
    @ToolCategory("Encoding & Crypto")
    public ToolCallback getPublicKey(SecureCredentialStore secureCredentialStore) {
        return FunctionToolCallback
                .builder("getPublicKey", (GetPublicKeyRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        throw new IllegalArgumentException("Authenticated user is required");
                    }
                    if (req == null || req.secretName() == null || req.secretName().isBlank()) {
                        throw new IllegalArgumentException("secretName is required");
                    }

                    String resolvedSecretName = resolveSecretNameFromRef(req.secretName());
                    String publicKeyBase64 = secureCredentialStore
                            .getSecretForUser(username, publicKeySecretName(resolvedSecretName));
                    if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
                        throw new IllegalArgumentException("No public key found for secretName: " + resolvedSecretName);
                    }

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "ok");
                    response.put("secretName", resolvedSecretName);
                    response.put("publicKeyBase64", publicKeyBase64);
                    response.put("javaHint", "Reconstruct using KeyFactory and X509EncodedKeySpec(Base64-decoded bytes).");
                    try {
                        return objectMapper.writeValueAsString(response);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("Unable to render public key response: " + ex.getMessage(), ex);
                    }
                })
                .description("Return the Base64-encoded X.509 public key bytes for a key identifier created by generatePrivateKey."
                        + " Use the same secretName/reference used for signing.")
                .inputType(GetPublicKeyRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("Encoding & Crypto")
    public ToolCallback signData() {
        ToolCallback delegate = FunctionToolCallback
                .builder("signData", (SignDataRequest req) -> {
                    if (req == null || req.data() == null || req.privateKey() == null || req.algorithm() == null) {
                        throw new IllegalArgumentException("data, privateKey, and algorithm are required");
                    }
                    try {
                        Signature signature = Signature.getInstance(req.algorithm().trim());
                        PrivateKey privateKey = parsePrivateKey(req.privateKey(), req.algorithm());
                        signature.initSign(privateKey);
                        signature.update(req.data().getBytes(StandardCharsets.UTF_8));
                        byte[] signed = signature.sign();
                        return Base64.getEncoder().encodeToString(signed);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("Unable to sign data: " + ex.getMessage(), ex);
                    }
                })
                .description("Sign UTF-8 string data using a private key and algorithm (for example SHA256withRSA). Returns Base64 signature text."
                    + " privateKey should normally be provided as a secret reference such as {{signing.key.main}} from generatePrivateKey.privateKeySecretRef."
                    + " Supported signature algorithms include SHA256withRSA and Ed25519.")
                .inputType(SignDataRequest.class)
                .build();

        return new VisualizableToolCallback(delegate, argumentsJson -> {
            try {
                var node = objectMapper.readTree(argumentsJson);
                String algorithm = node.path("algorithm").asText("(missing)");
                String data = node.path("data").asText("");
                String preview = data.length() <= 120 ? data : data.substring(0, 120) + "...";
                return "Sign data (private key hidden)\n"
                        + "- Algorithm: " + algorithm + "\n"
                        + "- Data Preview: " + preview;
            } catch (Exception ex) {
                return "Sign data (private key hidden)";
            }
        });
    }

    @Bean
    @ToolCategory("Encoding & Crypto")
    public ToolCallback verifyData() {
        return FunctionToolCallback
                .builder("verifyData", (VerifyDataRequest req) -> {
                    if (req == null || req.data() == null || req.publicKey() == null
                            || req.signature() == null || req.algorithm() == null) {
                        throw new IllegalArgumentException("data, publicKey, signature, and algorithm are required");
                    }
                    try {
                        Signature verifier = Signature.getInstance(req.algorithm().trim());
                        PublicKey publicKey = parsePublicKey(req.publicKey(), req.algorithm());
                        verifier.initVerify(publicKey);
                        verifier.update(req.data().getBytes(StandardCharsets.UTF_8));
                        boolean verified = verifier.verify(Base64.getDecoder().decode(req.signature().trim()));
                        return String.valueOf(verified);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("Unable to verify signature: " + ex.getMessage(), ex);
                    }
                })
                .description("Verify UTF-8 string data against a Base64 signature and public key using the provided algorithm. Returns true or false.")
                .inputType(VerifyDataRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Encoding & Crypto")
    public ToolCallback verifyDataByRef(SecureCredentialStore secureCredentialStore) {
        return FunctionToolCallback
                .builder("verifyDataByRef", (VerifyDataByRefRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        throw new IllegalArgumentException("Authenticated user is required");
                    }
                    if (req == null || req.data() == null || req.signature() == null
                            || req.algorithm() == null || req.secretName() == null) {
                        throw new IllegalArgumentException("data, signature, algorithm, and secretName are required");
                    }
                    String resolvedSecretName = resolveSecretNameFromRef(req.secretName());
                    String publicKeyBase64 = secureCredentialStore
                            .getSecretForUser(username, publicKeySecretName(resolvedSecretName));
                    if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
                        throw new IllegalArgumentException("No public key found for secretName: " + resolvedSecretName);
                    }

                    try {
                        Signature verifier = Signature.getInstance(req.algorithm().trim());
                        PublicKey publicKey = parsePublicKey(publicKeyBase64, req.algorithm());
                        verifier.initVerify(publicKey);
                        verifier.update(req.data().getBytes(StandardCharsets.UTF_8));
                        boolean verified = verifier.verify(Base64.getDecoder().decode(req.signature().trim()));
                        return String.valueOf(verified);
                    } catch (Exception ex) {
                        throw new IllegalArgumentException("Unable to verify signature by reference: " + ex.getMessage(), ex);
                    }
                })
                .description("Verify UTF-8 string data against a Base64 signature using the public key looked up by key identifier from generatePrivateKey."
                        + " Pass secretName as either the plain key name or {{secretName}} reference. Supports SHA256withRSA and Ed25519.")
                .inputType(VerifyDataByRefRequest.class)
                .build();
    }

            @Bean
            @Restricted
            @ToolCategory("Command Execution")
            public ToolCallback executeTerminalCommand(ExecuteTerminalCommandTool terminalTool) {
            ToolCallback delegate = FunctionToolCallback
                .builder("executeTerminalCommand", terminalTool::execute)
                .description(
                    """
                    Execute a terminal command through the virtual SSH environment and stream the live output back to the caller. Use this for shell workflows that require interactive terminal I/O.
                    """
                        .stripIndent())
                .inputType(ExecuteTerminalCommandRequest.class)
                .build();

            return new VisualizableToolCallback(delegate, terminalTool::formatAuthorizationDetails);
            }

            @Bean
            @ToolCategory("Command Execution")
            public ToolCallback executeCommandAndOutputToolCallback(ExecuteCommandAndOutputTool tool) {
            return FunctionToolCallback
                .builder("executeCommandAndOutputTool", tool::execute)
                .description("Executes a synchronous shell command, waits for it to complete, and returns the full output.")
                .inputType(ExecuteCommandAndOutputRequest.class)
                .build();
            }

            @Bean
            @ToolCategory("Command Execution")
            public ToolCallback startProcessToolCallback(StartProcessTool tool) {
            return FunctionToolCallback
                .builder("startProcessTool", tool::execute)
                .description("Starts a long-running background process and returns a reference PID to interact with it later.")
                .inputType(StartProcessRequest.class)
                .build();
            }

            @Bean
            @ToolCategory("Command Execution")
            public ToolCallback checkProcessToolCallback(CheckProcessTool tool) {
            return FunctionToolCallback
                .builder("checkProcessTool", tool::execute)
                .description("Checks if a background process is still running.")
                .inputType(CheckProcessRequest.class)
                .build();
            }

            @Bean
            @ToolCategory("Command Execution")
            public ToolCallback writeProcessToolCallback(WriteProcessTool tool) {
            return FunctionToolCallback
                .builder("writeProcessTool", tool::execute)
                .description("Writes input text to the stdin of a running background process.")
                .inputType(WriteProcessRequest.class)
                .build();
            }

            @Bean
            @ToolCategory("Command Execution")
            public ToolCallback readProcessToolCallback(ReadProcessTool tool) {
            return FunctionToolCallback
                .builder("readProcessTool", tool::execute)
                .description("Reads and drains available unread output from a background process.")
                .inputType(ReadProcessRequest.class)
                .build();
            }

            @Bean
            @ToolCategory("Command Execution")
            public ToolCallback stopProcessToolCallback(StopProcessTool tool) {
            return FunctionToolCallback
                .builder("stopProcessTool", tool::execute)
                .description("Terminates a background process and cleans up its memory footprint.")
                .inputType(StopProcessRequest.class)
                .build();
            }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback connectSsh(SshConnectTool sshConnectTool) {
        ToolCallback delegate = FunctionToolCallback
                .builder("connectSsh", sshConnectTool::execute)
                .description("""
                    Establish an SSH connection to a remote host and start an interactive shell session. \
                    REASONING_HINT: Include the host and alias in the authorization reasoning. \
                    Invoke this tool when the user says 'ssh <host>', 'connect <host>', or asks to connect to a server. \
                    The host may be specified as user@host:port, user@host, host:port, or just host — the user@ prefix is an SSH login username, never a friendly label. \
                    ALIAS_HINT: when the user says 'connect to X as Y' or 'call it Y', Y is a friendly alias for the connection — put Y in the 'alias' field and leave the username out of 'host' unless explicitly given. \
                    An optional alias can be provided to refer to the connection by a short name in subsequent tool calls."""
                        .stripIndent())
                .inputType(SshConnectRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, sshConnectTool::formatAuthorizationDetails);
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback createSshConnection(SshCreateConnectionTool sshCreateConnectionTool) {
        ToolCallback delegate = FunctionToolCallback
                .builder("createSshConnection", sshCreateConnectionTool::execute)
                .description("""
                    Save a new SSH connection by collecting hostname, port, username and credentials \
                    through a secure form — credentials never appear in the conversation history. \
                    REASONING_HINT: Use this tool when the user wants to add, register, or set up a new SSH server \
                    rather than connect to one immediately. \
                    After saving, the connection can be opened with the connectSsh tool. \
                    An optional alias can be provided to give the connection a friendly name."""
                        .stripIndent())
                .inputType(SshCreateConnectionRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, sshCreateConnectionTool::formatAuthorizationDetails);
    }
    public ToolCallback sshDownloadFile(DownloadFileTool downloadFileTool) {
        return FunctionToolCallback
                .builder("sshDownloadFile", downloadFileTool::execute)
                .description("""
                    Download a file from a remote SSH host to either Vork's file storage service (no extra \
                    authorization required) or a local filesystem path (requires explicit user authorization). \
                    REASONING_HINT: Include the remote file path and destination in the authorization reasoning. \
                    Requires an active SSH connection established with connectSsh."""
                        .stripIndent())
                .inputType(DownloadFileRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback sshUploadFile(UploadFileTool uploadFileTool) {
        return FunctionToolCallback
                .builder("sshUploadFile", uploadFileTool::execute)
                .description("""
                    Upload a file to a remote SSH host via SFTP. If the file is already in Vork's file storage \
                    service (specified by UUID or filename), it is uploaded immediately. If the filename refers \
                    to a local filesystem path, explicit user authorization is required first. \
                    REASONING_HINT: Include the file source and remote destination in the authorization reasoning. \
                    Requires an active SSH connection established with connectSsh."""
                        .stripIndent())
                .inputType(UploadFileRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback sshUploadTextFile(UploadTextFileTool uploadTextFileTool) {
        return FunctionToolCallback
                .builder("sshUploadTextFile", uploadTextFileTool::execute)
                .description("""
                    Write text content directly to a file on a remote SSH host via SFTP. \
                    The content is provided as a string and written as UTF-8. \
                    Use this instead of sshUploadFile when the content is already available as text \
                    (e.g. a generated script, config file, or document) rather than a stored file. \
                    REASONING_HINT: Include the remote destination path and a brief summary of the content in the authorization reasoning. \
                    Requires an active SSH connection established with connectSsh."""
                        .stripIndent())
                .inputType(UploadTextFileRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback createSessionTextFile(CreateSessionTextFileTool createSessionTextFileTool) {
        return FunctionToolCallback
                .builder("createSessionTextFile", createSessionTextFileTool::execute)
                .description("""
                    Create a UTF-8 text file in either the per-session sandbox (default) or the shared area.
                    Returns a download URL that can be rendered in chat attachments.
                    Use area=SESSION for files scoped to the current chat session, or area=SHARED for cross-session exchange.
                    Response guidance: do not paste raw download URLs in assistant text; generated files are auto-attached to the chat message.
                    """.stripIndent())
                .inputType(CreateSessionTextFileRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback writeFile(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("writeFile", sessionFileToolSuite::writeFile)
                .description("""
                    Write a UTF-8 file into the current session sandbox (default) or shared area.
                    Returns a direct download URL that can be rendered in chat attachments.
                    Use this for generating markdown, text, JSON, code, or configuration files.
                    Set attachToChat=false for intermediate files that should not appear in the assistant attachment list.
                    Response guidance: do not paste raw download URLs in assistant text; generated files are auto-attached to the chat message.
                    """.stripIndent())
                .inputType(WriteFileRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback writeBase64File(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("writeBase64File", sessionFileToolSuite::writeBase64File)
                .description("""
                    Write a binary file into the current session sandbox (default) or shared area from Base64 content.
                    The incoming base64Content is decoded to raw bytes before writing to disk.
                    Both standard Base64 and URL-safe Base64 are accepted automatically (no mode switch required).
                    Set attachToChat=false for intermediate files that should not appear in the assistant attachment list.
                    Response guidance: do not paste raw download URLs in assistant text; generated files are auto-attached to the chat message.
                    """.stripIndent())
                .inputType(WriteBase64FileRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback readFile(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("readFile", sessionFileToolSuite::readFile)
                .description("""
                    Read a file from the current session sandbox (default) or shared area.
                    Returns UTF-8 text content for text files and base64 for binary files.
                    """.stripIndent())
                .inputType(ReadFileRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback createFolder(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("createFolder", sessionFileToolSuite::createFolder)
                .description("""
                    Create a directory in the current session sandbox (default) or shared area.
                    Creates intermediate directories when necessary.
                    """.stripIndent())
                .inputType(CreateFolderRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback listFiles(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("listFiles", sessionFileToolSuite::listFiles)
                .description("""
                    List files/folders for a directory in the current session sandbox (default) or shared area.
                    File entries include download URLs.
                    """.stripIndent())
                .inputType(ListFilesRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback downloadFolderAsZip(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("downloadFolderAsZip", sessionFileToolSuite::downloadFolderAsZip)
                .description("""
                    Zip a folder in the current session sandbox (default) or shared area and return a download URL.
                    Use this when the user asks to download a directory as a single archive.
                    By default attachOnlyZip=true, so intermediate generated files are removed from the attachment list and only the zip is attached.
                    Set attachToChat=false if the zip should be generated without any chat attachment.
                    Response guidance: do not paste raw download URLs in assistant text; generated files are auto-attached to the chat message.
                    """.stripIndent())
                .inputType(DownloadFolderAsZipRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Files")
    public ToolCallback createPdf(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("createPdf", sessionFileToolSuite::createPdf)
                .description("""
                    Create a PDF file from MARKDOWN (default) or HTML content, store it in the session/shared file area,
                    and return a direct download URL.
                    Set attachToChat=false to generate the PDF without adding a chat attachment.
                    Response guidance: do not paste raw download URLs in assistant text; generated files are auto-attached to the chat message.
                    """.stripIndent())
                .inputType(CreatePdfRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Files")
    public ToolCallback fileExists(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("fileExists", sessionFileToolSuite::fileExists)
                .description("Check whether a file exists in the session/shared file area.")
                .inputType(FileExistsRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Files")
    public ToolCallback folderExists(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("folderExists", sessionFileToolSuite::folderExists)
                .description("Check whether a folder exists in the session/shared file area.")
                .inputType(FolderExistsRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Files")
    public ToolCallback extractZip(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("extractZip", sessionFileToolSuite::extractZip)
                .description("Extract a zip archive into the session/shared file area with safe path validation.")
                .inputType(ExtractZipRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Command Execution")
    public ToolCallback installCommand(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("installCommand", sessionFileToolSuite::installCommand)
                .description("Register a command binary directory under the session tools environment so local process execution can resolve it via PATH.")
                .inputType(InstallCommandRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Command Execution")
    public ToolCallback isCommandInstalled(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("isCommandInstalled", sessionFileToolSuite::isCommandInstalled)
                .description("Check whether a command is available in registered session command paths.")
                .inputType(IsCommandInstalledRequest.class)
                .build();
    }

    @Bean
    @Hidden
    @ToolCategory("Command Execution")
    public ToolCallback resolveArchitecture(SessionFileToolSuite sessionFileToolSuite) {
        return FunctionToolCallback
                .builder("resolveArchitecture", sessionFileToolSuite::resolveArchitecture)
                .description("Detect the runtime architecture for the current execution environment. No arguments required.")
                .inputType(ResolveArchitectureRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("SSH & File Transfer")
    public ToolCallback listSshConnections(ListSshConnectionsTool listSshConnectionsTool) {
        return FunctionToolCallback
                .builder("listSshConnections", listSshConnectionsTool::execute)
                .description("""
                    List all active SSH connections for the current session, showing each connection's \
                    alias and hostname. Invoke when the user asks which hosts are connected, \
                    or to see open SSH sessions."""
                        .stripIndent())
                .inputType(ListSshConnectionsRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("SSH & File Transfer")
    public ToolCallback setSshAlias(SetSshAliasTool setSshAliasTool) {
        return FunctionToolCallback
                .builder("setSshAlias", setSshAliasTool::execute)
                .description("""
                    Rename the alias of an existing SSH connection. \
                    REASONING_HINT: Include the current identifier and the new alias in the authorization reasoning. \
                    Invoke when the user says 'alias <host> as <name>' or 'rename connection <x> to <y>'. \
                    The hostOrAlias field accepts the current alias or hostname to identify the connection."""
                        .stripIndent())
                .inputType(SetSshAliasRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("SSH & File Transfer")
    public ToolCallback disconnectSsh(DisconnectSshTool disconnectSshTool) {
        return FunctionToolCallback
                .builder("disconnectSsh", disconnectSshTool::execute)
                .description("""
                    Close an active SSH connection and release all associated resources (terminal sessions, \
                    SFTP client, and the underlying SSH client). \
                    REASONING_HINT: Include the host or alias being disconnected in the authorization reasoning. \
                    Invoke when the user says 'disconnect <host>', 'close ssh <alias>', or 'exit <host>'."""
                        .stripIndent())
                .inputType(DisconnectSshRequest.class)
                .build();
    }

    @Bean
    @Restricted
    @ToolCategory("SSH & File Transfer")
    public ToolCallback deleteSshConnection(DeleteSshConnectionTool deleteSshConnectionTool) {
        ToolCallback delegate = FunctionToolCallback
                .builder("deleteSshConnection", deleteSshConnectionTool::execute)
                .description("""
                    Permanently delete a saved SSH connection (VorkNode) from Vork storage, \
                    and disconnect any active session to that host. This removes the stored \
                    host key, username, and credentials — the connection cannot be restored \
                    without reconnecting and re-verifying the host key. \
                    REASONING_HINT: Include the host or alias being deleted in the authorization reasoning. \
                    Invoke when the user says 'remove ssh <host>', 'forget <alias>', or 'delete connection <x>'."""
                        .stripIndent())
                .inputType(DeleteSshConnectionRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, deleteSshConnectionTool::formatAuthorizationDetails);
    }

    private static PrivateKey parsePrivateKey(String privateKeyValue, String signatureAlgorithm) {
        try {
            String keyAlgorithm = keyAlgorithmForSignature(signatureAlgorithm);
            byte[] keyBytes = decodeKeyMaterial(privateKeyValue);
            return KeyFactory.getInstance(keyAlgorithm).generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse privateKey: " + ex.getMessage(), ex);
        }
    }

    private static PublicKey parsePublicKey(String publicKeyValue, String signatureAlgorithm) {
        try {
            String keyAlgorithm = keyAlgorithmForSignature(signatureAlgorithm);
            byte[] keyBytes = decodeKeyMaterial(publicKeyValue);
            return KeyFactory.getInstance(keyAlgorithm).generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse publicKey: " + ex.getMessage(), ex);
        }
    }

    private static String keyAlgorithmForSignature(String signatureAlgorithm) {
        if (signatureAlgorithm == null || signatureAlgorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm is required");
        }
        String normalized = signatureAlgorithm.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("ED25519") || normalized.contains("EDDSA")) {
            return "Ed25519";
        }
        if (normalized.contains("RSA")) {
            return "RSA";
        }
        if (normalized.contains("ECDSA") || normalized.contains("EC")) {
            return "EC";
        }
        if (normalized.contains("DSA")) {
            return "DSA";
        }
        throw new IllegalArgumentException("Unsupported algorithm: " + signatureAlgorithm);
    }

    private static byte[] decodeKeyMaterial(String keyValue) {
        if (keyValue == null || keyValue.isBlank()) {
            throw new IllegalArgumentException("key value is required");
        }
        String normalized = keyValue
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "")
                .trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("key value is empty after normalization");
        }
        return Base64.getDecoder().decode(normalized);
    }

    private static String toPem(String type, byte[] derBytes) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(derBytes);
        return "-----BEGIN " + type + "-----\n"
                + base64
                + "\n-----END " + type + "-----";
    }

    private static String resolveSecretNameFromRef(String secretNameOrRef) {
        String value = secretNameOrRef == null ? "" : secretNameOrRef.trim();
        if (value.startsWith("{{") && value.endsWith("}}") && value.length() > 4) {
            return value.substring(2, value.length() - 2).trim();
        }
        return value;
    }

    private static String publicKeySecretName(String secretName) {
        String normalized = secretName.trim().toUpperCase(Locale.ROOT);
        // If it ends with _PRIVATE, replace with _PUBLIC; otherwise append _PUBLIC
        if (normalized.endsWith("_PRIVATE")) {
            return normalized.substring(0, normalized.length() - 8) + "_PUBLIC";
        }
        return normalized + "_PUBLIC";
    }

    private static String normalizeGeneratedSecretBaseName(String rawSecretName) {
        if (rawSecretName == null || rawSecretName.isBlank()) {
            throw new IllegalArgumentException("secretName is required");
        }

        String normalized = rawSecretName
                .trim()
                .replaceAll("\\s+", " ")
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);

        if (!normalized.matches("^[A-Z0-9_]+$")) {
            throw new IllegalArgumentException(
                    "secretName may only contain letters, numbers, and spaces (spaces become underscores)");
        }

        if (!normalized.matches(".*[A-Z0-9].*")) {
            throw new IllegalArgumentException("secretName must contain at least one letter or number");
        }

        return normalized;
    }

    private static String normalizeKeyAlgorithm(String keyAlgorithm) {
        if (keyAlgorithm == null || keyAlgorithm.isBlank()) {
            return "RSA";
        }
        String normalized = keyAlgorithm.trim().toUpperCase(Locale.ROOT);
        if ("RSA".equals(normalized)) {
            return "RSA";
        }
        if ("ED25519".equals(normalized) || "EDDSA".equals(normalized)) {
            return "Ed25519";
        }
        throw new IllegalArgumentException("Unsupported keyAlgorithm: " + keyAlgorithm + ". Supported: RSA, ED25519.");
    }

    private static String suggestedSigningAlgorithmForKeyAlgorithm(String keyAlgorithm) {
        if ("Ed25519".equals(keyAlgorithm)) {
            return "Ed25519";
        }
        return "SHA256withRSA";
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String formatCreateSkillAuthorizationDetails(String argumentsJson) {
        try {
            CreateSkillRequest req = objectMapper.readValue(argumentsJson, CreateSkillRequest.class);
            String name = req.name() == null || req.name().isBlank() ? "(missing)" : req.name().trim();
            String groupUuid = req.groupUuid() == null || req.groupUuid().isBlank() ? "(missing)" : req.groupUuid().trim();
            String description = req.description() == null || req.description().isBlank()
                    ? "(none)"
                    : req.description().trim();
            if (description.length() > 160) {
                description = description.substring(0, 160) + "...";
            }

            int parameterCount = req.parameters() == null ? 0 : req.parameters().size();
            int toolCount = req.allowedTools() == null ? 0 : req.allowedTools().size();
            int typeCount = req.allowedTypes() == null ? 0 : req.allowedTypes().size();
            int subSkillCount = req.subSkillUuids() == null ? 0 : req.subSkillUuids().size();
            int secretCount = req.secrets() == null ? 0 : req.secrets().size();

            StringBuilder markdown = new StringBuilder();
            markdown.append("## Create Skill\n\n");
            markdown.append("- **Name:** ").append(name).append("\n");
            markdown.append("- **Group UUID:** ").append(groupUuid).append("\n");
            markdown.append("- **Description:** ").append(description).append("\n");
                markdown.append("- **Visibility:** ").append(req.visibilityEffective()).append("\n");
            markdown.append("- **Parameters:** ").append(parameterCount).append("\n");
            markdown.append("- **Allowed Tools:** ").append(toolCount).append("\n");
            markdown.append("- **Allowed Types:** ").append(typeCount).append("\n");
            markdown.append("- **Sub-skills:** ").append(subSkillCount).append("\n");
            markdown.append("- **Secrets:** ").append(secretCount).append("\n");
            return markdown.toString();
        } catch (Exception ex) {
            return argumentsJson;
        }
    }

    /**
     * {@code httpRequest} tool — a generic HTTP client that replaces the old
     * {@code getURLContents} tool.  Supports all common methods, custom headers,
     * and a request body so the model can interact with REST APIs, fetch web pages,
     * and submit forms.
     */
    @Bean
    @ToolCategory("Web")
    public ToolCallback httpRequest(OAuthClientService oauthClientService,
                                    SessionFileSystem sessionFileSystem) {
        return FunctionToolCallback
                .builder("httpRequest", (HttpRequestToolRequest req) -> {
                    if (req == null || req.url() == null || req.url().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"url is required\"}";
                    }

                    try {
                        URI uri = URI.create(req.url().trim());
                        String scheme = uri.getScheme();
                        if (scheme == null
                                || (!"http".equalsIgnoreCase(scheme)
                                    && !"https".equalsIgnoreCase(scheme))) {
                            return "{\"status\":\"error\",\"message\":\"Only http and https URLs are supported\"}";
                        }

                        String method = req.method() != null && !req.method().isBlank()
                                ? req.method().trim().toUpperCase()
                                : "GET";

                        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "vork-ai-tool/1.0");

                        String username = resolveUsername();

                        // Apply caller-supplied headers (User-Agent may be overridden)
                        if (req.headers() != null) {
                            for (Map.Entry<String, String> entry : req.headers().entrySet()) {
                                String value = oauthClientService.resolveHeaderValue(username, entry.getValue());
                                builder.header(entry.getKey(), value);
                            }
                        }

                        // Body publisher
                        HttpRequest.BodyPublisher bodyPublisher =
                                (req.body() != null && !req.body().isEmpty())
                                ? HttpRequest.BodyPublishers.ofString(req.body())
                                : HttpRequest.BodyPublishers.noBody();

                        builder.method(method, bodyPublisher);

                        boolean binaryMode = req.responseMode() != null
                                && "BINARY".equalsIgnoreCase(req.responseMode().trim());

                        if (binaryMode) {
                            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                                    .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

                            if (req.saveToPath() == null || req.saveToPath().isBlank()) {
                                return "{\"status\":\"error\",\"message\":\"saveToPath is required when responseMode=BINARY\"}";
                            }

                            FileArea area = parseFileArea(req.area());
                            String sessionUuid = resolveSessionUuid();
                            if (area == FileArea.SESSION && (sessionUuid == null || sessionUuid.isBlank() || "system".equals(sessionUuid))) {
                                return "{\"status\":\"error\",\"message\":\"Session context is required for SESSION binary downloads\"}";
                            }
                            String owner = area == FileArea.SESSION ? sessionUuid : null;

                            byte[] bodyBytes = response.body() == null ? new byte[0] : response.body();
                            FileDescriptor descriptor = sessionFileSystem.write(
                                    area,
                                    owner,
                                    req.saveToPath(),
                                    new ByteArrayInputStream(bodyBytes),
                                    bodyBytes.length);

                            Map<String, Object> responseHeaders = new java.util.LinkedHashMap<>();
                            response.headers().map().forEach((k, vals) -> {
                                if (vals.size() == 1) {
                                    responseHeaders.put(k, vals.get(0));
                                } else {
                                    responseHeaders.put(k, vals);
                                }
                            });

                            Map<String, Object> result = new java.util.LinkedHashMap<>();
                            result.put("statusCode", response.statusCode());
                            result.put("headers", responseHeaders);
                            result.put("saved", true);
                            result.put("area", descriptor.area().name());
                            result.put("path", descriptor.path());
                            result.put("sizeBytes", descriptor.sizeBytes());
                            result.put("downloadUrl", descriptor.downloadUrl());
                            return objectMapper.writeValueAsString(result);
                        }

                        HttpResponse<String> response = HttpClient.newHttpClient()
                                .send(builder.build(), HttpResponse.BodyHandlers.ofString());

                        // Collect response headers as a Map<String, List<String>>,
                        // but flatten single-value headers to strings for readability
                        Map<String, Object> responseHeaders = new java.util.LinkedHashMap<>();
                        response.headers().map().forEach((k, vals) -> {
                            if (vals.size() == 1) {
                                responseHeaders.put(k, vals.get(0));
                            } else {
                                responseHeaders.put(k, vals);
                            }
                        });

                        String content = response.body() == null ? "" : response.body();
                        if (content.length() > 20_000) {
                            content = content.substring(0, 20_000) + "\n...<truncated>";
                        }

                        Map<String, Object> result = new java.util.LinkedHashMap<>();
                        result.put("statusCode", response.statusCode());
                        result.put("headers", responseHeaders);
                        result.put("body", content);
                        return objectMapper.writeValueAsString(result);

                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("""
                    Send an HTTP request and return the response status code, headers, and body.
                    Supports GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS.
                    Use this to interact with REST APIs, fetch web pages, or submit forms.
                    For GET requests put query parameters in the URL.
                    For POST/PUT/PATCH supply the body as a string and set the Content-Type header.
                    Set responseMode=BINARY with saveToPath to download binary content into session/shared storage.
                    The response body is truncated to 20 000 characters.
                    """.stripIndent())
                .inputType(HttpRequestToolRequest.class)
                .build();
    }

    /**
     * {@code createMongoDBConnection} tool — stores and validates a MongoDB
     * connection profile for the authenticated user.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    public ToolCallback createMongoDBConnection(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("createMongoDBConnection", (CreateMongoDbConnectionRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }

                    CreateMongoDbConnectionRequest effectiveReq = req != null
                            ? req
                            : new CreateMongoDbConnectionRequest(null, null, null, null, null, null, null, null, null);

                    if (needsMongoConnectionInput(effectiveReq)) {
                        throw new ToolSuspensionException(
                                "createMongoDBConnection",
                                safeJson(effectiveReq),
                                "MongoDB connection details are required before saving credentials.",
                                mongoConnectionInputForm(effectiveReq));
                    }

                    return mongoToolService.createConnection(username, effectiveReq);
                })
                .description(
                        """
                        Create and save a user-scoped MongoDB connection profile for generic CRM/data access tools.
                        Credentials are stored in encrypted user secrets and never need to be repeated after setup.
                        This tool validates connectivity with a ping before saving.
                        Credentials are always collected via a secure user form and should never be passed directly in tool arguments.
                        If required fields are missing, this tool suspends and requests host, port, database, and credentials via a secure form.
                        Use connectionName to save multiple profiles (defaults to 'default').
                        REASONING_HINT: Authorization is required to save MongoDB connection '{{connectionName}}'.
                        """.stripIndent())
                .inputType(CreateMongoDbConnectionRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                String name = node.path("connectionName").asText("default");
                String db = node.path("database").asText("");
                return "Create MongoDB connection profile: " + name + (db.isBlank() ? "" : " (db=" + db + ")");
            } catch (Exception ex) {
                return "Create MongoDB connection profile";
            }
        });
    }

    private static boolean needsMongoConnectionInput(CreateMongoDbConnectionRequest req) {
        if (req == null) {
            return true;
        }
        if (!Boolean.TRUE.equals(req.credentialPromptComplete())) {
            return true;
        }
        boolean hasConnectionString = req.connectionString() != null && !req.connectionString().isBlank();
        if (hasConnectionString) {
            return false;
        }
        return req.host() == null || req.host().isBlank()
            || req.database() == null || req.database().isBlank();
    }

    private static InteractionFormSchema mongoConnectionInputForm(CreateMongoDbConnectionRequest req) {
        String connectionName = req.connectionName() != null && !req.connectionName().isBlank()
            ? req.connectionName() : "default";
        String host = req.host() != null && !req.host().isBlank() ? req.host() : "localhost";
        String port = String.valueOf(req.port() != null && req.port() > 0 ? req.port() : 27017);
        String database = req.database() != null && !req.database().isBlank() ? req.database() : "";
        String authDatabase = req.authDatabase() != null && !req.authDatabase().isBlank() ? req.authDatabase() : "admin";
        String username = req.username() != null ? req.username() : "";

        return new InteractionFormSchema(
            "COLLECT_MONGODB_CONNECTION_INPUT",
            "MongoDB Connection Required",
            "Provide MongoDB connection details. You can use either a full connectionString or host/port/database fields.",
            List.of(
                new FormField("connectionName", "TEXT", "Connection Name", "default", connectionName, true, FieldSource.CONTEXT, null),
                new FormField("connectionString", "TEXT", "connectionString (optional)",
                    "mongodb://user:pass@host:27017/database", req.connectionString(), false, FieldSource.CONTEXT, null),
                new FormField("host", "TEXT", "Host", "localhost", host, false, FieldSource.CONTEXT, null),
                new FormField("port", "NUMBER", "Port", "27017", port, false, FieldSource.CONTEXT, null),
                new FormField("database", "TEXT", "Database", "crm", database, false, FieldSource.CONTEXT, null),
                new FormField("authDatabase", "TEXT", "Auth Database", "admin", authDatabase, false, FieldSource.CONTEXT, null),
                new FormField("username", "TEXT", "Username", "mongodb-user", username, false, FieldSource.SECRET, null),
                new FormField("password", "PASSWORD", "Password", "MongoDB password", null, false, FieldSource.SECRET, null),
                new FormField("credentialPromptComplete", "HIDDEN", "credentialPromptComplete", "true", "true", false, FieldSource.CONTEXT, null)
            ),
            List.of(
                new FormAction("ONCE", "Save & Continue", "primary"),
                new FormAction("DENIED", "Cancel", "danger")
            )
        );
    }

    /**
     * {@code listMongoDBCollections} tool — lists collections for a saved MongoDB
     * connection profile.
     */
    @Bean
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection"})
    public ToolCallback listMongoDBCollections(MongoToolService mongoToolService) {
        return FunctionToolCallback
                .builder("listMongoDBCollections", (ListMongoDbCollectionsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.listCollections(username, req);
                })
                .description(
                        """
                        List collections in a MongoDB database using a previously saved connection profile.
                        Read-only and unrestricted.
                        """.stripIndent())
                .inputType(ListMongoDbCollectionsRequest.class)
                .build();
    }

    /**
     * {@code getMongoDBCollectionSchema} tool — infers schema hints by sampling
     * documents from a resolved collection.
     */
    @Bean
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback getMongoDBCollectionSchema(MongoToolService mongoToolService) {
        return FunctionToolCallback
                .builder("getMongoDBCollectionSchema", (GetMongoDbCollectionSchemaRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.getCollectionSchema(username, req);
                })
                .description(
                        """
                        Infer a collection schema by sampling documents.
                        The tool can resolve collection name from natural language query context when collection is omitted.
                        Read-only and unrestricted.
                        """.stripIndent())
                .inputType(GetMongoDbCollectionSchemaRequest.class)
                .build();
    }

    /**
     * {@code searchMongoDBDocuments} tool — generic read/search against external
     * MongoDB collections.
     */
    @Bean
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback searchMongoDBDocuments(MongoToolService mongoToolService) {
        return FunctionToolCallback
                .builder("searchMongoDBDocuments", (SearchMongoDbDocumentsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.searchDocuments(username, req);
                })
                .description(
                        """
                        Search/read MongoDB documents using either raw filterJson or natural language query.
                        When filterJson is omitted, query is used to build a fuzzy text filter and resolve collection names.
                        Supports pagination and sorting.
                        Read-only and unrestricted.
                        """.stripIndent())
                .inputType(SearchMongoDbDocumentsRequest.class)
                .build();
    }

    /**
     * {@code insertMongoDBDocument} tool — insert one JSON document into a
     * collection.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection"})
    public ToolCallback insertMongoDBDocument(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("insertMongoDBDocument", (InsertMongoDbDocumentRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.insertDocument(username, req);
                })
                .description(
                        """
                        Insert a document into a MongoDB collection for a saved connection profile.
                        This is a write operation and requires authorization.
                        REASONING_HINT: Authorization is required to insert data into '{{collection}}'.
                        """.stripIndent())
                .inputType(InsertMongoDbDocumentRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                return "Insert MongoDB document into collection: " + node.path("collection").asText("(unknown)");
            } catch (Exception ex) {
                return "Insert MongoDB document";
            }
        });
    }

    /**
     * {@code updateMongoDBDocuments} tool — update one or many documents.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback updateMongoDBDocuments(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("updateMongoDBDocuments", (UpdateMongoDbDocumentsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.updateDocuments(username, req);
                })
                .description(
                        """
                        Update MongoDB documents using raw filterJson or natural language query.
                        updateJson must be a MongoDB update document (for example with $set).
                        This is a write operation and requires authorization.
                        REASONING_HINT: Authorization is required to update MongoDB collection '{{collection}}'.
                        """.stripIndent())
                .inputType(UpdateMongoDbDocumentsRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                return "Update MongoDB documents in collection: " + node.path("collection").asText("(resolved from query)");
            } catch (Exception ex) {
                return "Update MongoDB documents";
            }
        });
    }

    /**
     * {@code deleteMongoDBDocuments} tool — delete one or many documents.
     */
    @Bean
    @Restricted
    @ToolCategory("Data & Integrations")
    @ToolDepends({"createMongoDBConnection", "listMongoDBCollections"})
    public ToolCallback deleteMongoDBDocuments(MongoToolService mongoToolService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("deleteMongoDBDocuments", (DeleteMongoDbDocumentsRequest req) -> {
                    String username = resolveUsername();
                    if (username == null || username.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"Authenticated user is required\"}";
                    }
                    return mongoToolService.deleteDocuments(username, req);
                })
                .description(
                        """
                        Delete MongoDB documents using raw filterJson or natural language query.
                        This is a write operation and requires authorization.
                        REASONING_HINT: Authorization is required to delete records from '{{collection}}'.
                        """.stripIndent())
                .inputType(DeleteMongoDbDocumentsRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argsJson -> {
            try {
                var node = objectMapper.readTree(argsJson);
                return "Delete MongoDB documents from collection: " + node.path("collection").asText("(resolved from query)");
            } catch (Exception ex) {
                return "Delete MongoDB documents";
            }
        });
    }

    /**
     * {@code logInfo} tool — writes a message to server logs at INFO level.
     */
    @Bean
    @ToolCategory("Diagnostics")
    public ToolCallback logInfo() {
        return FunctionToolCallback
                .builder("logInfo", (LogInfoRequest req) -> {
                    String message = req == null ? null : req.message();
                    if (message == null || message.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"message is required\"}";
                    }
                    log.info("AI logInfo tool message: {}", message);
                    return "{\"status\":\"ok\"}";
                })
                .description(
                        """
                        Write a provided message to application logs at INFO level.
                        """
                                .stripIndent())
                .inputType(LogInfoRequest.class)
                .build();
    }

    /**
     * {@code compileJavaType} tool — compiles a Java schema from source code
     * supplied by the model, persists it to MongoDB, and loads it into the
     * running JVM so it is available for subsequent operations.
     *
     * <p>
     * The tool returns a small JSON object:
     * <ul>
    * <li>{@code {"status":"ok","class":"jadaptive.crm.Customer","group":"jadaptive.crm"}} on success.</li>
     * <li>{@code {"status":"error","message":"..."}} on failure.</li>
    * <li>{@code {"status":"confirm_required","message":"..."}} when group must be confirmed.</li>
     * </ul>
     */
    @Bean
    @Restricted
    @ToolCategory("Schema & Types")
    public ToolCallback compileJavaType(TypeGeneratorService typeGeneratorService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("compileJavaType", (CompileTypeRequest req) -> {
                    String group;
                    try {
                        group = normalizeCompileTypeGroup(req.group());
                    } catch (IllegalArgumentException ex) {
                        return "{\"status\":\"error\",\"message\":\""
                                + ex.getMessage().replace("\"", "'") + "\"}";
                    }

                    if (group == null) {
                        return "{\"status\":\"confirm_required\",\"message\":\"Please confirm target group package (for example: jadaptive.crm). Dots are allowed; sh.vork.* is not allowed.\"}";
                    }

                    String source = req.source();
                    if (source == null || source.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"source is required\"}";
                    }

                    try {
                        String normalizedSource = rewritePackageForGroup(source, group);
                        Class<?> clazz = typeGeneratorService.compileAndSave(normalizedSource);
                        return "{\"status\":\"ok\",\"class\":\"" + clazz.getName() + "\",\"group\":\"" + group + "\"}";
                    } catch (TypeGenerationException e) {
                        return "{\"status\":\"error\",\"message\":\"" +
                                e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
Compile a Java schema (record, class, interface, or enum) from source code and load it into the running application. 
The schema is persisted to MongoDB and will be available after a restart. 
Returns the fully-qualified class name on success. 
If a type implements sh.vork.orm.DatabaseEntity, uuid must be String (String uuid(); and field/component type String), never java.util.UUID. 
Any record should implement sh.vork.orm.DatabaseEntity. 
All types must use the confirmed group package (for example jadaptive.crm) and must not be under sh.vork.*.
When generating a record that will be managed in the Data Inspector UI, annotate record components with @sh.vork.typegen.DisplayField to control table columns and form rendering. Example: @DisplayField(label="Full Name", order=1, tableColumn=true, inputType="text", required=true). Fields not annotated with tableColumn=true will not appear in the table but will still appear in the create/edit form. Use tableColumn=false for nested records, long text, and list fields.
Embedded value-object types (e.g. Address, LineItem) that are only used as nested fields inside a parent record MUST NOT implement DatabaseEntity and MUST NOT have a uuid field. Only top-level records that are stored and queried independently should implement DatabaseEntity. This distinction controls which types appear in the Data Inspector dropdown.
When generating an enum and there is enough context to give each constant a human-readable display name (e.g. country names, status labels, category titles), always add a single String constructor field and a getLabel() accessor: private final String label; EnumName(String label) { this.label = label; } public String getLabel() { return label; }. This enables the Data Inspector to show readable labels in dropdowns and table columns instead of raw constant names. If there is no meaningful display name beyond the constant name itself, omit the field.
If a user asks in natural language to "create a record", "create an enum", "define a record", or "model a record", use this tool.
REASONING_HINT: Authorization is required to compile {{type_name}} record/enum schema.
                                """
                                .stripIndent())
                .inputType(CompileTypeRequest.class)
                .build();

        return new VisualizableToolCallback(delegate, argumentsJson -> {
            try {
                String sourceCode = objectMapper.readTree(argumentsJson)
                        .path("source")
                        .asText();
                if (sourceCode == null || sourceCode.isBlank()) {
                    return argumentsJson;
                }
                return sourceCode;
            } catch (Exception ex) {
                return argumentsJson;
            }
        });
    }

    private static String normalizeCompileTypeGroup(String rawGroup) {
        if (rawGroup == null || rawGroup.isBlank()) {
            return null;
        }

        String group = rawGroup.trim();
        if (group.startsWith(".") || group.endsWith(".")) {
            throw new IllegalArgumentException("group must not start or end with '.'.");
        }

        String lowered = group.toLowerCase(Locale.ROOT);
        if ("sh.vork".equals(lowered) || lowered.startsWith("sh.vork.")) {
            throw new IllegalArgumentException("group must not be under sh.vork.*");
        }

        String[] segments = group.split("\\.");
        if (segments.length == 0) {
            throw new IllegalArgumentException("group is invalid.");
        }
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                throw new IllegalArgumentException("group contains an empty package segment.");
            }
            if (!Character.isJavaIdentifierStart(segment.charAt(0))) {
                throw new IllegalArgumentException("group segment must start with a valid Java identifier character: " + segment);
            }
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i))) {
                    throw new IllegalArgumentException("group segment contains invalid character: " + segment);
                }
            }
        }
        return group;
    }

    private static String rewritePackageForGroup(String source, String groupPackage) {
        Pattern pattern = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;");
        Matcher matcher = pattern.matcher(source);
        String packageLine = "package " + groupPackage + ";";
        if (matcher.find()) {
            return matcher.replaceFirst(packageLine);
        }
        return packageLine + "\n\n" + source;
    }

    /**
     * {@code listJavaTypes} tool — returns all custom schemas that have been
     * compiled and persisted to MongoDB via {@link #compileJavaType}.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback listJavaTypes(DatabaseRepository<JavaType> javaTypeRepository) {
        return FunctionToolCallback
                .builder("listJavaTypes", (ListJavaTypesRequest req) -> {
                    List<String> entries = new ArrayList<>();
                    try (var stream = javaTypeRepository.list(0, Integer.MAX_VALUE)) {
                        stream.forEach(jt -> entries.add(
                                "{\"fqn\":\"" + jt.uuid() + "\"," +
                                        "\"classFiles\":" + jt.bytecode().size() + "," +
                                        "\"createdAt\":\"" + new java.util.Date(jt.createdAt()) + "\"}"));
                    }
                    if (entries.isEmpty()) {
                        return "{\"types\":[]}";
                    }
                    return "{\"types\":[" + String.join(",", entries) + "]}";
                })
                .description(
                        """
                                List all custom schemas (records/enums/classes) that have been compiled and persisted to MongoDB. Returns each schema's fully-qualified class name, number of class files (including inner classes), and the date it was first created.
                                """
                                .stripIndent())
                .inputType(ListJavaTypesRequest.class)
                .build();
    }

    /**
     * {@code discoverExportableTypes} tool — lists all built-in annotated types and
     * runtime-compiled custom types that can be exported.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback discoverExportableTypes(TypeExportService typeExportService) {
        return FunctionToolCallback
                .builder("discoverExportableTypes", (DiscoverExportableTypesRequest req) -> {
                    try {
                        return objectMapper.writeValueAsString(
                                Map.of("types", typeExportService.discoverExportableTypes()));
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Discover all exportable Java types. Built-in types are listed only if explicitly marked with @ExportableType. Runtime-compiled custom types are also listed.
                                """
                                .stripIndent())
                .inputType(DiscoverExportableTypesRequest.class)
                .build();
    }

    /**
     * {@code exportJavaType} tool — exports persisted data for one exportable entity type.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback exportJavaType(TypeExportService typeExportService) {
        return FunctionToolCallback
                .builder("exportJavaType", (ExportJavaTypeRequest req) -> {
                    try {
                        return objectMapper.writeValueAsString(
                                typeExportService.exportTypeData(req.fqn(), req.mode(), req.uuid()));
                    } catch (IllegalArgumentException e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Export persisted JSON data for an exportable DatabaseEntity type by fully-qualified class name.
                                Built-in types are exportable only when explicitly marked with @ExportableType.
                                Modes:
                                - BY_ID (default): export exactly one instance by uuid (uuid required)
                                - ALL: export all persisted instances for that type
                                """
                                .stripIndent())
                .inputType(ExportJavaTypeRequest.class)
                .build();
    }

    /**
     * {@code exportAllJavaTypeData} tool — exports all persisted data across all exportable entity types.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback exportAllJavaTypeData(TypeExportService typeExportService) {
        return FunctionToolCallback
                .builder("exportAllJavaTypeData", (ExportAllJavaTypeDataRequest req) -> {
                    try {
                        return objectMapper.writeValueAsString(typeExportService.exportAllTypeData());
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Export all persisted JSON data across all exportable entity types.
                                Use this for full backup/export snapshots.
                                """
                                .stripIndent())
                .inputType(ExportAllJavaTypeDataRequest.class)
                .build();
    }

    /**
     * {@code exportJavaTypeSource} tool — exports source separately from data for runtime-compiled custom types.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback exportJavaTypeSource(TypeExportService typeExportService) {
        return FunctionToolCallback
                .builder("exportJavaTypeSource", (ExportJavaTypeSourceRequest req) -> {
                    try {
                        return objectMapper.writeValueAsString(typeExportService.exportTypeSource(req.fqn()));
                    } catch (IllegalArgumentException e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description(
                        """
                                Export source code separately from data for a runtime-compiled custom type.
                                Built-in application types do not expose source through this tool.
                                """
                                .stripIndent())
                .inputType(ExportJavaTypeSourceRequest.class)
                .build();
    }

    /**
     * {@code getJavaTypeSource} tool — retrieves the stored Java source for a
     * compiled type, allowing the model to read it before making targeted edits.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback getJavaTypeSource(DatabaseRepository<JavaType> javaTypeRepository) {
        return FunctionToolCallback
                .builder("getJavaTypeSource", (GetTypeSchemaRequest req) -> {
                    JavaType jt = javaTypeRepository.get(req.fqn());
                    if (jt == null) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                    return "{\"fqn\":\"" + jt.uuid() + "\",\"source\":" +
                            objectMapper.valueToTree(jt.source()).toString() + "}";
                })
                .description(
                        """
                                Retrieve the stored Java source code for a compiled schema by its fully-qualified class name. \
                                Use this before modifying a record/enum so you can read the existing definition and make targeted changes \
                                rather than rewriting it from scratch.
                                """
                                .stripIndent())
                .inputType(GetTypeSchemaRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // TypeDatabase CRUD tools
    // -------------------------------------------------------------------------

    /**
     * {@code getTypeSchema} tool — returns a JSON schema derived from the record's
     * components, so the model knows exactly what fields and types to supply.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback getTypeSchema() {
        return FunctionToolCallback
                .builder("getTypeSchema", (GetTypeSchemaRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        return "{\"schema\":" + buildSchema(clazz) + "}";
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        """
                                Get the JSON field schema for a custom record by its fully-qualified class name. Use listJavaTypes first to discover available schemas.
                                """
                                .stripIndent())
                .inputType(GetTypeSchemaRequest.class)
                .build();
    }


    /**
     * {@code listEnumValues} tool — returns all declared constants of an enum
     * class resolved via {@link JavaTypeClassLoader}.
     */
    @Bean
    @ToolCategory("Schema & Types")
    public ToolCallback listEnumValues() {
        return FunctionToolCallback
                .builder("listEnumValues", (ListEnumValuesRequest req) -> {
                    try {
                        Class<?> clazz = typeClassLoader.loadClass(req.fqn());
                        if (!clazz.isEnum()) {
                            return "{\"status\":\"error\",\"message\":\"" + req.fqn() + " is not an enum\"}";
                        }
                        Object[] constants = clazz.getEnumConstants();
                        StringBuilder sb = new StringBuilder("{\"fqn\":\"");
                        sb.append(req.fqn()).append("\",\"values\":[");
                        for (int i = 0; i < constants.length; i++) {
                            if (i > 0)
                                sb.append(',');
                            sb.append('\"').append(constants[i].toString()).append('\"');
                        }
                        sb.append("]}");
                        return sb.toString();
                    } catch (ClassNotFoundException e) {
                        return "{\"status\":\"error\",\"message\":\"Type not found: " + req.fqn() + "\"}";
                    }
                })
                .description(
                        """
                                List all declared constants of an enum by its fully-qualified class name. Use listJavaTypes to discover available types first.
                                """
                                .stripIndent())
                .inputType(ListEnumValuesRequest.class)
                .build();
    }

    // -------------------------------------------------------------------------
    // JSON schema helpers
    // -------------------------------------------------------------------------

    private static String buildSchema(Class<?> clazz) {
        if (clazz == String.class)
            return "{\"type\":\"string\"}";
        if (clazz == int.class || clazz == Integer.class ||
                clazz == long.class || clazz == Long.class)
            return "{\"type\":\"integer\"}";
        if (clazz == double.class || clazz == Double.class ||
                clazz == float.class || clazz == Float.class ||
                clazz == java.math.BigDecimal.class)
            return "{\"type\":\"number\"}";
        if (clazz == boolean.class || clazz == Boolean.class)
            return "{\"type\":\"boolean\"}";
        if (clazz.isRecord()) {
            StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"title\":\"")
                    .append(clazz.getSimpleName()).append("\",\"properties\":{");
            RecordComponent[] comps = clazz.getRecordComponents();
            for (int i = 0; i < comps.length; i++) {
                if (i > 0)
                    sb.append(',');
                sb.append('"').append(comps[i].getName()).append("\":");
                sb.append(schemaForType(comps[i].getType(), comps[i].getGenericType()));
            }
            sb.append("}}");
            return sb.toString();
        }
        return "{\"type\":\"object\"}";
    }

    private static String schemaForType(Class<?> type, Type generic) {
        if (type == List.class || type == java.util.Collection.class) {
            String itemSchema = "{\"type\":\"object\"}";
            if (generic instanceof ParameterizedType pt) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c)
                    itemSchema = buildSchema(c);
            }
            return "{\"type\":\"array\",\"items\":" + itemSchema + "}";
        }
        return buildSchema(type);
    }

    @Bean
    @ToolCategory("Attention")
    public ToolCallback createAttentionAlert(AttentionAlertService attentionAlertService,
                                             ChannelService channelService) {
        return FunctionToolCallback
                .builder("createAttentionAlert", (CreateAttentionAlertToolRequest req) -> {
                    if (req == null) {
                        return "{\"status\":\"error\",\"message\":\"request is required\"}";
                    }

                    LinkedHashSet<String> resolvedChannels = new LinkedHashSet<>();

                    if (req.selectedChannelName() != null && !req.selectedChannelName().isBlank()) {
                        channelService.resolveByChannelName(req.selectedChannelName())
                                .ifPresent(ref -> resolvedChannels.add(ref.channelName()));
                        if (resolvedChannels.isEmpty()) {
                            return "{\"status\":\"error\",\"message\":\"Selected channel not found: "
                                    + req.selectedChannelName().replace("\"", "'") + "\"}";
                        }
                    }

                    List<String> requestedChannels = req.channelNames() == null ? List.of() : req.channelNames();
                    if (resolvedChannels.isEmpty() && requestedChannels.isEmpty()) {
                        String username = resolveUsername();
                        if (username == null || username.isBlank()) {
                            return "{\"status\":\"error\",\"message\":\"channelNames is required when no authenticated user exists\"}";
                        }
                        resolvedChannels.add(username);
                    }

                    for (String requested : requestedChannels) {
                        if (requested == null || requested.isBlank()) {
                            continue;
                        }

                        var exact = channelService.resolveByChannelName(requested);
                        if (exact.isPresent()) {
                            resolvedChannels.add(exact.get().channelName());
                            continue;
                        }

                        List<ChannelRef> candidates = channelService.search(requested, 8);
                        if (candidates.isEmpty()) {
                            return "{\"status\":\"error\",\"message\":\"Unknown channel: "
                                    + requested.replace("\"", "'") + "\"}";
                        }
                        if (candidates.size() > 1) {
                            throw new ToolSuspensionException(
                                    "createAttentionAlert",
                                    safeJson(req),
                                    "Multiple channels matched '" + requested + "'. Please choose one.",
                                    buildAttentionChannelSelectionForm(requested, candidates));
                        }
                        resolvedChannels.add(candidates.getFirst().channelName());
                    }

                    AttentionResolutionPolicy policy;
                    try {
                        policy = parseResolutionPolicy(req.resolutionPolicy(), req.actionUrl());
                    } catch (IllegalArgumentException ex) {
                        String provided = req.resolutionPolicy() == null ? "null"
                                : req.resolutionPolicy().replace("\"", "'");
                        return "{\"status\":\"error\",\"message\":\"Invalid resolutionPolicy: "
                                + provided
                            + ". Valid values: ACTION_REQUIRED, DISMISSABLE. Do not use FIRST_ACK or ALL_ACK.\"}";
                    }

                    if (policy == AttentionResolutionPolicy.ACTION_REQUIRED
                            && (req.actionUrl() == null || req.actionUrl().isBlank())) {
                        return "{\"status\":\"error\",\"message\":\"actionUrl is required when resolutionPolicy is ACTION_REQUIRED\"}";
                    }

                    AttentionSourceType sourceType;
                    try {
                        sourceType = req.sourceType() == null || req.sourceType().isBlank()
                                ? AttentionSourceType.CUSTOM
                                : AttentionSourceType.valueOf(req.sourceType().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        return "{\"status\":\"error\",\"message\":\"Invalid sourceType: "
                                + req.sourceType().replace("\"", "'") + "\"}";
                    }

                    AttentionAlert created = attentionAlertService.create(
                            new AttentionAlertService.CreateAttentionAlertCommand(
                                    List.copyOf(resolvedChannels),
                                    req.alertName(),
                                    req.description(),
                                    policy,
                                    req.actionUrl(),
                                    req.attentionAt() == null ? 0L : req.attentionAt(),
                                    sourceType,
                                    req.sourceId()));

                        try {
                        return objectMapper.writeValueAsString(Map.of(
                            "status", "ok",
                            "alertUuid", created.uuid(),
                            "channels", created.channelNames(),
                            "policy", created.resolutionPolicy().name(),
                            "sourceType", created.sourceType().name()));
                        } catch (Exception ex) {
                        return "{\"status\":\"error\",\"message\":\""
                            + ex.getMessage().replace("\"", "'") + "\"}";
                        }
                })
                .description("Create an attention alert for one or more channels. Channel names are globally unique and case-insensitive."
                    + " Resolution policy must be ACTION_REQUIRED or DISMISSABLE."
                    + " Prefer DISMISSABLE when there is no actionUrl, and use ACTION_REQUIRED when actionUrl is present."
                    + " Never use FIRST_ACK or ALL_ACK."
                    + " If a channel query is ambiguous, this tool suspends and asks the user to choose the intended channel.")
                .inputType(CreateAttentionAlertToolRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Attention")
    public ToolCallback requestInformation(RequestInformationService requestInformationService,
                                           DatabaseRepository<AiSession> sessionRepository) {
        return FunctionToolCallback
                .builder("requestInformation", (RequestInformationToolRequest req) -> {
                    if (req == null) {
                        return "{\"status\":\"error\",\"message\":\"request is required\"}";
                    }

                    if (req.requestCampaignId() != null && !req.requestCampaignId().isBlank()) {
                        boolean hasAggregatedResponses = req.responseCount() != null
                                && req.responseCount() > 0
                                && req.responsesJson() != null
                                && !req.responsesJson().isBlank()
                                && !"{}".equals(req.responsesJson().trim())
                                && !"[]".equals(req.responsesJson().trim());
                        if (hasAggregatedResponses) {
                            try {
                                var campaign = requestInformationService.getCampaign(req.requestCampaignId());
                                if (campaign.status() == RequestCampaignStatus.SATISFIED) {
                                    try {
                                        return objectMapper.writeValueAsString(Map.of(
                                                "status", "ok",
                                                "campaignId", req.requestCampaignId(),
                                                "responseCount", req.responseCount() == null ? 0 : req.responseCount(),
                                                "responsesJson", req.responsesJson() == null ? "[]" : req.responsesJson()));
                                    } catch (Exception ex) {
                                        String msg = ex.getMessage() == null ? "Failed to encode campaign response payload"
                                                : ex.getMessage().replace("\"", "'");
                                        return "{\"status\":\"error\",\"message\":\"" + msg + "\"}";
                                    }
                                }
                            } catch (Exception ex) {
                                log.debug("requestInformation resume shortcut ignored: campaign not resolvable [campaignId={}]",
                                        req.requestCampaignId());
                            }
                        } else {
                            log.debug("requestInformation ignored internal resume fields without aggregated responses [campaignId={}]",
                                    req.requestCampaignId());
                        }
                    }

                    if (req.channelNames() == null || req.channelNames().isEmpty()) {
                        return "{\"status\":\"error\",\"message\":\"channelNames is required\"}";
                    }
                    if (req.promptText() == null || req.promptText().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"promptText is required\"}";
                    }
                    if (req.requesterMessage() == null || req.requesterMessage().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"requesterMessage is required\"}";
                    }
                    if (req.recipientMessage() == null || req.recipientMessage().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"recipientMessage is required\"}";
                    }

                    String sessionUuid = ToolExecutionContext.getSessionUuid();
                    if (sessionUuid == null || sessionUuid.isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"requestInformation must run inside a bound AI session\"}";
                    }

                    AiSession session = sessionRepository.get(sessionUuid);
                    if (session == null) {
                        return "{\"status\":\"error\",\"message\":\"AI session not found: "
                                + sessionUuid.replace("\"", "'") + "\"}";
                    }

                    RequestResponsePolicy requestedPolicy;
                    try {
                        requestedPolicy = req.responsePolicy() == null || req.responsePolicy().isBlank()
                                ? RequestResponsePolicy.AUTO
                                : RequestResponsePolicy.valueOf(req.responsePolicy().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ex) {
                        return "{\"status\":\"error\",\"message\":\"Invalid responsePolicy: "
                                + req.responsePolicy().replace("\"", "'")
                                + ". Valid values: AUTO, FIRST, ALL, QUORUM\"}";
                    }

                    if (req.alertResolutionPolicy() != null && !req.alertResolutionPolicy().isBlank()) {
                        try {
                            AttentionResolutionPolicy.valueOf(req.alertResolutionPolicy().trim().toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException ex) {
                            return "{\"status\":\"error\",\"message\":\"Invalid alertResolutionPolicy: "
                                    + req.alertResolutionPolicy().replace("\"", "'")
                                    + ". Valid values: ACTION_REQUIRED, DISMISSABLE\"}";
                        }
                    }

                            InteractionFormSchema schema = new InteractionFormSchema(
                                "REQUEST_INFORMATION",
                                req.alertName() == null || req.alertName().isBlank() ? "Request Information" : req.alertName().trim(),
                                req.promptText().trim(),
                                List.of(new FormField(
                                    "response",
                                    "TEXTAREA",
                                    "Response",
                                    "",
                                    "Provide the requested information.",
                                    true,
                                    FieldSource.CONVERSATION,
                                    null)),
                                List.of(new FormAction("SUBMIT", "Submit", "primary")));

                            ToolSuspensionException.SuspensionCampaign campaign =
                                new ToolSuspensionException.SuspensionCampaign(
                                    req.channelNames(),
                                    requestedPolicy,
                                    req.quorumCount(),
                                    req.sendNotifications(),
                                    req.alertName(),
                                        req.recipientMessage(),
                                    req.alertResolutionPolicy(),
                                    req.attentionAt());

                    throw new ToolSuspensionException(
                            "requestInformation",
                            safeJson(req),
                                    req.requesterMessage().trim(),
                                schema,
                                campaign);
                })
                .description("Request information from one or more channels by generating per-recipient input links, creating attention alerts, and optionally sending out-of-band notifications."
                                + " requesterMessage and recipientMessage are required and must be explicitly authored for each call."
                        + " Response policy can be AUTO, FIRST, ALL, or QUORUM."
                        + " The tool suspends the current session until the response threshold is met.")
                .inputType(RequestInformationToolRequest.class)
                .build();
    }

    private static AttentionResolutionPolicy parseResolutionPolicy(String rawPolicy, String actionUrl) {
        if (rawPolicy == null || rawPolicy.isBlank()) {
            return actionUrl == null || actionUrl.isBlank()
                    ? AttentionResolutionPolicy.DISMISSABLE
                    : AttentionResolutionPolicy.ACTION_REQUIRED;
        }

        return AttentionResolutionPolicy.valueOf(rawPolicy.trim().toUpperCase(Locale.ROOT));
    }

    private static InteractionFormSchema buildAttentionChannelSelectionForm(String query,
                                                                             List<ChannelRef> candidates) {
        List<FormAction> actions = List.of(new FormAction("ONCE", "Create Alert", "primary"));
        List<String> options = new ArrayList<>();
        for (ChannelRef candidate : candidates) {
            options.add(candidate.channelName());
        }

        List<FormField> fields = List.of(
                new FormField(
                        "selectedChannelName",
                        "SELECT",
                        "Channel",
                        "",
                        "Multiple channels matched '" + query + "'. Select the target channel.",
                        true,
                        FieldSource.CONVERSATION,
                        options));

        return new InteractionFormSchema(
                "ATTENTION_CHANNEL_SELECTION",
                "Select Channel",
                "Choose exactly one channel for this alert.",
                fields,
                actions);
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    /**
     * {@code listNotificationProviders} tool — discovers configured notification
     * providers that support sending to arbitrary (unregistered) addresses.
     *
     * <p>The AI should call this before {@code sendNotification} to determine which
     * providers are available and what address types each one accepts.
     */
    @Bean
    @ToolCategory("Notifications")
    public ToolCallback listNotificationProviders(DirectNotificationService directNotificationService) {
        return FunctionToolCallback
                .builder("listNotificationProviders",
                        (ListNotificationProvidersRequest req) -> {
                            log.debug("Tool listNotificationProviders invoked");
                            try {
                                var providers = directNotificationService.listAvailable();
                                return objectMapper.writeValueAsString(providers);
                            } catch (Exception e) {
                                return "{\"status\":\"error\",\"message\":\""
                                        + e.getMessage().replace("\"", "'") + "\"}";
                            }
                        })
                .description(
                        "List all configured notification providers that can send to arbitrary "
                        + "addresses (email, SMS). Returns each provider's configId, displayName, "
                        + "providerKey, and supported mediaTypes (EMAIL_ADDRESS, PHONE_NUMBER). "
                        + "Call this before sendNotification to choose the right provider.")
                .inputType(ListNotificationProvidersRequest.class)
                .build();
    }

    /**
     * {@code listNotificationLedgerEntries} tool — returns notification send
     * ledger rows for troubleshooting and audit.
     */
    @Bean
    @Restricted
    @ToolCategory("Notifications")
    public ToolCallback listNotificationLedgerEntries(DatabaseRepository<NotificationLedgerEntry> notificationLedgerRepository) {
        return FunctionToolCallback
                .builder("listNotificationLedgerEntries",
                        (ListNotificationLedgerEntriesRequest req) -> {
                            log.debug("Tool listNotificationLedgerEntries invoked: req={}", req);
                            try {
                                int page = req == null || req.page() == null ? 0 : Math.max(0, req.page());
                                int pageSize = req == null || req.pageSize() == null ? 50 : Math.max(1, req.pageSize());

                                List<SearchQuery> filters = new ArrayList<>();
                                if (req != null && req.finalState() != null && !req.finalState().isBlank()) {
                                    filters.add(SearchQuery.eq("finalState", req.finalState().trim().toUpperCase(Locale.ROOT)));
                                }
                                if (req != null && req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
                                    filters.add(SearchQuery.eq("idempotencyKey", req.idempotencyKey().trim()));
                                }
                                if (req != null && req.destination() != null && !req.destination().isBlank()) {
                                    filters.add(SearchQuery.eq("destination", req.destination().trim()));
                                }
                                if (req != null && req.providerConfigId() != null && !req.providerConfigId().isBlank()) {
                                    filters.add(SearchQuery.eq("providerConfigId", req.providerConfigId().trim()));
                                }

                                SearchQuery[] searchQueries = filters.toArray(SearchQuery[]::new);
                                long total = notificationLedgerRepository.searchCount(searchQueries);
                                List<NotificationLedgerEntry> entries;
                                try (var stream = notificationLedgerRepository.search(
                                        page,
                                        pageSize,
                                        "createdAt",
                                        SortOrder.DESC,
                                        searchQueries)) {
                                    entries = stream.toList();
                                }

                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put("status", "ok");
                                payload.put("total", total);
                                payload.put("page", page);
                                payload.put("pageSize", pageSize);
                                payload.put("entries", entries);
                                return objectMapper.writeValueAsString(payload);
                            } catch (Exception e) {
                                return "{\"status\":\"error\",\"message\":\""
                                        + (e.getMessage() == null ? "ledger query failed" : e.getMessage().replace("\"", "'"))
                                        + "\"}";
                            }
                        })
                .description(
                        "List notification delivery ledger entries for troubleshooting and audit. "
                                + "Supports optional filters: finalState (SENT|FAILED|ALREADY_SENT), "
                                + "idempotencyKey, destination, and providerConfigId. "
                                + "Returns paged entries sorted by createdAt descending.")
                .inputType(ListNotificationLedgerEntriesRequest.class)
                .build();
    }

    /**
     * {@code summarizeNotificationLedger} tool — returns aggregate delivery
     * health stats without returning full ledger rows.
     */
    @Bean
    @Restricted
    @ToolCategory("Notifications")
    public ToolCallback summarizeNotificationLedger(DatabaseRepository<NotificationLedgerEntry> notificationLedgerRepository) {
        return FunctionToolCallback
                .builder("summarizeNotificationLedger",
                        (SummarizeNotificationLedgerRequest req) -> {
                            log.debug("Tool summarizeNotificationLedger invoked: req={}", req);
                            try {
                                List<SearchQuery> filters = new ArrayList<>();
                                if (req != null && req.providerConfigId() != null && !req.providerConfigId().isBlank()) {
                                    filters.add(SearchQuery.eq("providerConfigId", req.providerConfigId().trim()));
                                }
                                if (req != null && req.idempotencyGroup() != null && !req.idempotencyGroup().isBlank()) {
                                    filters.add(SearchQuery.eq("idempotencyGroup", req.idempotencyGroup().trim()));
                                }
                                if (req != null && req.destination() != null && !req.destination().isBlank()) {
                                    filters.add(SearchQuery.eq("destination", req.destination().trim()));
                                }
                                if (req != null && req.sinceEpochMillis() != null) {
                                    filters.add(SearchQuery.gte("createdAt", req.sinceEpochMillis()));
                                }

                                SearchQuery[] searchQueries = filters.toArray(SearchQuery[]::new);
                                long total = notificationLedgerRepository.searchCount(searchQueries);

                                Map<String, Long> byFinalState = new LinkedHashMap<>();
                                Map<String, Long> byProviderKey = new LinkedHashMap<>();
                                Map<String, Long> byMediaType = new LinkedHashMap<>();
                                java.util.Set<String> uniqueDestinations = new java.util.HashSet<>();
                                long duplicateSuppressedCount = 0L;

                                final int pageSize = 500;
                                int page = 0;
                                while (true) {
                                    List<NotificationLedgerEntry> entries;
                                    try (var stream = notificationLedgerRepository.search(
                                            page,
                                            pageSize,
                                            "createdAt",
                                            SortOrder.DESC,
                                            searchQueries)) {
                                        entries = stream.toList();
                                    }

                                    if (entries.isEmpty()) {
                                        break;
                                    }

                                    for (NotificationLedgerEntry entry : entries) {
                                        String finalState = entry.finalState() == null
                                                ? "UNKNOWN"
                                                : entry.finalState().name();
                                        byFinalState.merge(finalState, 1L, Long::sum);

                                        String providerKey = entry.providerKey() == null || entry.providerKey().isBlank()
                                                ? "UNKNOWN"
                                                : entry.providerKey();
                                        byProviderKey.merge(providerKey, 1L, Long::sum);

                                        String mediaType = entry.mediaType() == null || entry.mediaType().isBlank()
                                                ? "UNKNOWN"
                                                : entry.mediaType();
                                        byMediaType.merge(mediaType, 1L, Long::sum);

                                        if (entry.destination() != null && !entry.destination().isBlank()) {
                                            uniqueDestinations.add(entry.destination());
                                        }

                                        if ("ALREADY_SENT".equals(finalState)) {
                                            duplicateSuppressedCount++;
                                        }
                                    }

                                    if (entries.size() < pageSize) {
                                        break;
                                    }
                                    page++;
                                }

                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put("status", "ok");
                                payload.put("total", total);
                                payload.put("duplicateSuppressedCount", duplicateSuppressedCount);
                                payload.put("uniqueDestinationCount", uniqueDestinations.size());
                                payload.put("byFinalState", byFinalState);
                                payload.put("byProviderKey", byProviderKey);
                                payload.put("byMediaType", byMediaType);
                                payload.put("appliedFilters", req == null ? Map.of() : req);
                                return objectMapper.writeValueAsString(payload);
                            } catch (Exception e) {
                                return "{\"status\":\"error\",\"message\":\""
                                        + (e.getMessage() == null ? "ledger summary failed" : e.getMessage().replace("\"", "'"))
                                        + "\"}";
                            }
                        })
                .description(
                        "Summarize notification ledger delivery outcomes as aggregate counts. "
                                + "Returns totals and grouped counts by finalState, providerKey, and mediaType, "
                                + "plus duplicateSuppressedCount and uniqueDestinationCount. "
                                + "Optional filters: sinceEpochMillis, providerConfigId, idempotencyGroup, destination.")
                .inputType(SummarizeNotificationLedgerRequest.class)
                .build();
    }

    /**
     * {@code sendNotification} tool — sends a notification to an arbitrary address
     * using a specific configured provider.
     *
     * <p>Requires prior approval ({@link Restricted}) because it delivers messages
     * to external addresses.
     */
    @Bean
    @Restricted
    @ToolCategory("Notifications")
    @ToolDepends({"listNotificationProviders"})
    public ToolCallback sendNotification(DirectNotificationService directNotificationService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("sendNotification",
                        (SendNotificationRequest req) -> {
                            log.debug("Tool sendNotification invoked: providerConfigId={}, address={}",
                                    req.providerConfigId(), req.address());
                            DirectNotificationService.SendResult result = directNotificationService.send(
                                    req.providerConfigId(),
                                    req.title(),
                                    req.body(),
                                    req.bodyContentType(),
                                    req.idempotencyGroup(),
                                    req.originatingAgent(),
                                    req.originatingSkill(),
                                    req.address());
                            try {
                                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                                payload.put("status", result.status());
                                if (result.message() != null && !result.message().isBlank()) {
                                    payload.put("message", result.message());
                                }
                                if (result.ledgerEntryId() != null && !result.ledgerEntryId().isBlank()) {
                                    payload.put("ledgerEntryId", result.ledgerEntryId());
                                }
                                if (result.idempotencyKey() != null && !result.idempotencyKey().isBlank()) {
                                    payload.put("idempotencyKey", result.idempotencyKey());
                                }
                                return objectMapper.writeValueAsString(payload);
                            } catch (Exception e) {
                                return "{\"status\":\"error\",\"message\":\""
                                        + (e.getMessage() == null ? "serialization failed" : e.getMessage().replace("\"", "'"))
                                        + "\"}";
                            }
                        })
                .description(
                        "Send a notification to an arbitrary email address or phone number. "
                        + "Call listNotificationProviders first to get a valid providerConfigId "
                        + "and confirm the address type is supported. "
                    + "For email providers, set bodyContentType=text/html to send HTML email. "
                        + "Optional idempotencyGroup suppresses duplicate successful sends to the same "
                        + "mediaType+address and returns status=already sent when deduplicated. "
                        + "address must match the provider type: email address for email providers, "
                        + "E.164 phone number (e.g. +14155552671) for SMS providers.")
                .inputType(SendNotificationRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, argumentsJson -> {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(argumentsJson);
                String providerConfigId = node.path("providerConfigId").asText(null);
                String to      = node.path("address").asText("(unknown)");
                String subject = node.path("title").asText("(no subject)");
                String body    = node.path("body").asText("");
                return directNotificationService.formatDirectNotification(providerConfigId, to, subject, body);
            } catch (Exception ex) {
                return argumentsJson;
            }
        });
    }

    private static String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            return "system";
        }
        return sessionUuid;
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    private static FileArea parseFileArea(String rawArea) {
        if (rawArea == null || rawArea.isBlank()) {
            return FileArea.SESSION;
        }
        try {
            return FileArea.valueOf(rawArea.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return FileArea.SESSION;
        }
    }

    // -------------------------------------------------------------------------
    // Knowledge Base Tools
    // -------------------------------------------------------------------------

    @Bean
    @Restricted
    @ToolCategory("Knowledge")
    public ToolCallback defineKnowledge(KnowledgeService knowledgeService) {
        ToolCallback delegate = FunctionToolCallback
                .builder("defineKnowledge", (DefineKnowledgeRequest req) -> {
                    if (req == null || req.base() == null || req.base().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"base is required\"}";
                    }
                    if (req.content() == null || req.content().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"content is required\"}";
                    }

                    try {
                        KnowledgeEntry entry = knowledgeService.define(req.base().trim(), req.content().trim());
                        return objectMapper.writeValueAsString(Map.of(
                                "status", "ok",
                                "uuid", entry.uuid(),
                                "base", entry.base(),
                                "createdAt", entry.createdAt()));
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Persistently define a new knowledge article in the knowledge base. The base is a category name (e.g. 'Deployment', 'Troubleshooting'). Content is the free-text article.")
                .inputType(DefineKnowledgeRequest.class)
                .build();
        return new VisualizableToolCallback(delegate, this::formatDefineKnowledgeAuthorizationDetails);
    }

    @Bean
    @ToolCategory("Knowledge")
    public ToolCallback searchKnowledge(KnowledgeService knowledgeService) {
        return FunctionToolCallback
                .builder("searchKnowledge", (SearchKnowledgeRequest req) -> {
                    if (req == null || req.base() == null || req.base().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"base is required\"}";
                    }
                    if (req.query() == null || req.query().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"query is required\"}";
                    }

                    try {
                        List<Map<String, Object>> results = new ArrayList<>();
                        try (var stream = knowledgeService.search(req.base().trim(), req.query().trim(), 0, 50)) {
                            stream.forEach(entry -> results.add(Map.of(
                                    "uuid", entry.uuid(),
                                    "base", entry.base(),
                                    "content", entry.content(),
                                    "createdAt", entry.createdAt(),
                                    "updatedAt", entry.updatedAt())));
                        }
                        return objectMapper.writeValueAsString(Map.of(
                                "status", "ok",
                                "results", results,
                                "count", results.size()));
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Search knowledge base articles by category and keyword. Returns matching articles with content, timestamps, and UUIDs.")
                .inputType(SearchKnowledgeRequest.class)
                .build();
    }

    @Bean
    @ToolCategory("Knowledge")
    public ToolCallback getKnowledge(KnowledgeService knowledgeService) {
        return FunctionToolCallback
                .builder("getKnowledge", (GetKnowledgeRequest req) -> {
                    if (req == null || req.base() == null || req.base().isBlank()) {
                        return "{\"status\":\"error\",\"message\":\"base is required\"}";
                    }

                    try {
                        List<Map<String, Object>> results = new ArrayList<>();
                        try (var stream = knowledgeService.getAll(req.base().trim(), 0, 100)) {
                            stream.forEach(entry -> results.add(Map.of(
                                    "uuid", entry.uuid(),
                                    "base", entry.base(),
                                    "content", entry.content(),
                                    "createdAt", entry.createdAt(),
                                    "updatedAt", entry.updatedAt())));
                        }
                        return objectMapper.writeValueAsString(Map.of(
                                "status", "ok",
                                "results", results,
                                "count", results.size()));
                    } catch (Exception e) {
                        return "{\"status\":\"error\",\"message\":\""
                                + e.getMessage().replace("\"", "'") + "\"}";
                    }
                })
                .description("Retrieve all knowledge base articles in a given category, sorted by creation date (newest first).")
                .inputType(GetKnowledgeRequest.class)
                .build();
    }

    private String formatDefineKnowledgeAuthorizationDetails(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(
                    argumentsJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            String base = (String) args.get("base");
            String content = (String) args.get("content");
            if (content != null && content.length() > 200) {
                content = content.substring(0, 200) + "…";
            }
            return String.format("Define knowledge entry in base '%s':\n\n%s", base, content);
        } catch (Exception e) {
            return "Define knowledge entry";
        }
    }

}
