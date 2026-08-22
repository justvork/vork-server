package sh.vork.ai.controller;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import jakarta.servlet.http.HttpSession;
import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.ai.request.RequestInformationService;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.binding.BindingCatalogService;
import sh.vork.binding.BindingSummary;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.service.McpBindingService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionService;
import sh.vork.skill.Skill;
import sh.vork.web.RequestOriginContext;

/**
 * Handles both HTTP session initialisation and WebSocket chat messages.
 *
 * <h3>HTTP</h3>
 * {@code GET /api/chat/session} — called on page load.  Returns the session UUID
 * and full message history so the browser can render prior turns.
 *
 * <h3>WebSocket / STOMP</h3>
 * Client sends to {@code /app/chat.send}; the server broadcasts the AI response
 * to {@code /topic/chat/{sessionUuid}}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService            chatService;
    private final SimpMessagingTemplate  messaging;
    private final AiOrchestrationService aiOrchestrationService;
    private final TerminalStreamRouter   terminalStreamRouter;
    private final ToolRegistry           toolRegistry;
    private final DatabaseRepository<Skill> skillRepo;
    private final SessionEnvironmentService sessionEnvironmentService;
    private final ReflectionService reflectionService;
    private final BindingCatalogService bindingCatalogService;
    private final McpBindingService mcpBindingService;
    private final RequestInformationService requestInformationService;



    public ChatController(ChatService chatService, SimpMessagingTemplate messaging,
                          AiOrchestrationService aiOrchestrationService,
                          TerminalStreamRouter terminalStreamRouter,
                          ToolRegistry toolRegistry,
                          DatabaseRepository<Skill> skillRepository,
                          SessionEnvironmentService sessionEnvironmentService,
                          ReflectionService reflectionService,
                          BindingCatalogService bindingCatalogService,
                          McpBindingService mcpBindingService,
                          RequestInformationService requestInformationService) {
        this.chatService = chatService;
        this.messaging   = messaging;
        this.aiOrchestrationService = aiOrchestrationService;
        this.terminalStreamRouter = terminalStreamRouter;
        this.toolRegistry = toolRegistry;
        this.skillRepo = skillRepository;
        this.sessionEnvironmentService = sessionEnvironmentService;
        this.reflectionService = reflectionService;
        this.bindingCatalogService = bindingCatalogService;
        this.mcpBindingService = mcpBindingService;
        this.requestInformationService = requestInformationService;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @GetMapping("/session")
    public SessionResponse getSession(
            HttpServletRequest request,
            HttpSession httpSession,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider,
            @RequestParam(required = false) String sessionUuid,
            @RequestParam(required = false) String modelId) {
        AiSession session = (sessionUuid == null || sessionUuid.isBlank())
                ? chatService.getOrCreateSession(httpSession.getId(), provider, modelId)
                : chatService.getSessionForCurrentUser(sessionUuid);
        persistRequestBaseUrl(session.uuid(), request);
        return new SessionResponse(session.uuid(), session.name(), session.provider(),
            session.activeAgentTemplateId(), session.messages(), session.modelId(), session.status(),
            session.originMode() != null ? session.originMode().name() : null,
            isChildCampaignSession(session));
    }

    @GetMapping("/session/new")
    public SessionResponse createSession(
            HttpServletRequest request,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String agentTemplateId) {
        AiSession session = chatService.createNewSession(provider, modelId, agentTemplateId);
        persistRequestBaseUrl(session.uuid(), request);
        return new SessionResponse(session.uuid(), session.name(), session.provider(),
            session.activeAgentTemplateId(), session.messages(), session.modelId(), session.status(),
            session.originMode() != null ? session.originMode().name() : null,
            isChildCampaignSession(session));
    }

    @GetMapping("/sessions")
    public List<SessionSummaryResponse> listSessions(@RequestParam(required = false) String agentTemplateId,
                                                     @RequestParam(required = false) String search,
                                                     @RequestParam(required = false) Integer limit) {
        return chatService.listSessionsForCurrentUser(agentTemplateId, search, limit)
                .stream()
                .sorted(Comparator.comparingLong(AiSession::createdAt).reversed())
                .map(session -> new SessionSummaryResponse(
                        session.uuid(),
                        session.name(),
                        session.provider(),
                        session.createdAt(),
                        session.messages() == null ? 0 : session.messages().size(),
                        session.modelId(),
                        session.activeAgentTemplateId()))
                .toList();
    }

    @GetMapping("/session/{sessionUuid}/request-campaign")
    public ResponseEntity<?> getActiveRequestCampaign(@PathVariable String sessionUuid) {
        try {
            AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            var campaign = requestInformationService.findOpenCampaignForSession(session.uuid());
            if (campaign == null) {
                var latest = requestInformationService.findLatestCampaignForSession(session.uuid());
                if (latest == null) {
                    return ResponseEntity.ok(Map.of("status", "NONE"));
                }
                java.util.Map<String, Object> latestResponse = new java.util.LinkedHashMap<>();
                latestResponse.put("status", latest.status().name());
                latestResponse.put("campaignUuid", latest.uuid());
                if (latest.eventId() != null && !latest.eventId().isBlank()) {
                    latestResponse.put("eventId", latest.eventId());
                }
                latestResponse.put("policy", latest.policy().name());
                latestResponse.put("requiredResponses", latest.requiredResponses());
                latestResponse.put("respondedCount", latest.respondedChannels() == null ? 0 : latest.respondedChannels().size());
                latestResponse.put("targetChannels", latest.targetChannels() == null ? List.of() : latest.targetChannels());
                latestResponse.put("promptText", latest.promptText() == null ? "" : latest.promptText());
                latestResponse.put("routeMode", latest.responseRouteMode().name());
                latestResponse.put("parentSessionUuid", latest.parentSessionUuid());
                latestResponse.put("childSessionRoutingEnabled", latest.childSessionRoutingEnabled());
                latestResponse.put("childSessionLinks", latest.childSessionUuidsByChannel());
                latestResponse.put("childSessionCount", latest.childSessionUuidsByChannel() == null ? 0 : latest.childSessionUuidsByChannel().size());
                return ResponseEntity.ok(latestResponse);
            }
            java.util.Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("status", "OPEN");
            response.put("campaignUuid", campaign.uuid());
            if (campaign.eventId() != null && !campaign.eventId().isBlank()) {
                response.put("eventId", campaign.eventId());
            }
            response.put("policy", campaign.policy().name());
            response.put("requiredResponses", campaign.requiredResponses());
            response.put("respondedCount", campaign.respondedChannels() == null ? 0 : campaign.respondedChannels().size());
            response.put("targetChannels", campaign.targetChannels() == null ? List.of() : campaign.targetChannels());
            response.put("promptText", campaign.promptText() == null ? "" : campaign.promptText());
            response.put("routeMode", campaign.responseRouteMode().name());
            response.put("parentSessionUuid", campaign.parentSessionUuid());
            response.put("childSessionRoutingEnabled", campaign.childSessionRoutingEnabled());
            response.put("childSessionLinks", campaign.childSessionUuidsByChannel());
            response.put("childSessionCount", campaign.childSessionUuidsByChannel() == null ? 0 : campaign.childSessionUuidsByChannel().size());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @GetMapping("/session/{sessionUuid}/messages")
    public ResponseEntity<?> pollSessionMessages(@PathVariable String sessionUuid,
                                                 @RequestParam(required = false) String afterMessageUuid) {
        try {
            AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            List<AiChatMessage> messages = session.messages() == null ? List.of() : session.messages();

            if (afterMessageUuid != null && !afterMessageUuid.isBlank()) {
                int startIndex = -1;
                for (int i = 0; i < messages.size(); i++) {
                    AiChatMessage msg = messages.get(i);
                    if (msg != null && afterMessageUuid.equals(msg.uuid())) {
                        startIndex = i + 1;
                        break;
                    }
                }
                messages = startIndex >= 0 ? messages.subList(startIndex, messages.size()) : messages;
            }

            return ResponseEntity.ok(new SessionMessagesResponse(
                    session.uuid(),
                    messages,
                    session.status(),
                    isChildCampaignSession(session)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @PostMapping("/session/{sessionUuid}/request-campaign/{campaignUuid}/respond")
    public ResponseEntity<?> submitChildCampaignResponse(@PathVariable String sessionUuid,
                                                         @PathVariable String campaignUuid,
                                                         @RequestBody CampaignResponseRequest request,
                                                         java.security.Principal principal) {
        try {
            AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            if (!isChildCampaignSession(session)) {
                return ResponseEntity.status(400).body(Map.of("status", "ERROR", "message", "Session is not a child campaign session."));
            }

            String envCampaignId = session.environmentVariables() == null
                    ? null
                    : session.environmentVariables().get("REQUEST_CAMPAIGN_ID");
            if (envCampaignId == null || envCampaignId.isBlank() || !envCampaignId.equals(campaignUuid)) {
                return ResponseEntity.status(400).body(Map.of("status", "ERROR", "message", "Campaign does not match this child session."));
            }

            String content = request == null || request.message() == null ? "" : request.message().trim();
            if (content.isBlank()) {
                return ResponseEntity.status(400).body(Map.of("status", "ERROR", "message", "Response message is required."));
            }

            String username = (principal != null && principal.getName() != null) ? principal.getName() : session.username();
            AiChatMessage response = chatService.sendMessageAsUser(username, sessionUuid, content, List.of(), null);
            if (response != null) {
                messaging.convertAndSend("/topic/chat/" + sessionUuid, response);
            }

            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @PostMapping("/session/{sessionUuid}/request-campaign/{campaignUuid}/cancel")
    public ResponseEntity<?> cancelRequestCampaign(@PathVariable String sessionUuid,
                                                   @PathVariable String campaignUuid) {
        try {
            AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            var campaign = requestInformationService.getCampaign(campaignUuid);
            if (!session.uuid().equals(campaign.sessionUuid())) {
                return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", "Campaign does not belong to this session."));
            }

            boolean cancelled = requestInformationService.cancelCampaign(campaignUuid);
            if (!cancelled) {
                return ResponseEntity.ok(Map.of("status", "NOOP", "message", "Campaign was already closed."));
            }

            chatService.releaseAwaitingInputSession(sessionUuid,
                    "External information request was cancelled. You can continue this session.");

            messaging.convertAndSend("/topic/chat/" + sessionUuid,
                    new UiEventFrame(UUID.randomUUID().toString(),
                            "TEXT_RESPONSE",
                            "CHAT_OUTPUT",
                            "External information request was cancelled. You can continue this session.",
                            null));

            return ResponseEntity.ok(Map.of("status", "CANCELLED", "campaignUuid", campaignUuid));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

            @PostMapping("/session/{sessionUuid}/name")
            public SessionSummaryResponse renameSession(@PathVariable String sessionUuid,
                                @RequestBody RenameSessionRequest request) {
            AiSession session = chatService.renameSessionForCurrentUser(sessionUuid,
                request == null ? null : request.name());
            return new SessionSummaryResponse(
                session.uuid(),
                session.name(),
                session.provider(),
                session.createdAt(),
                session.messages() == null ? 0 : session.messages().size(),
                session.modelId(),
                session.activeAgentTemplateId());
            }

    @PostMapping("/session/{sessionUuid}/agent")
    public ResponseEntity<?> switchAgent(@PathVariable String sessionUuid,
                                          @RequestBody Map<String, String> body) {
        String agentTemplateId = body == null ? null : body.get("agentTemplateId");
        if (agentTemplateId == null || agentTemplateId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "ERROR", "message", "agentTemplateId required"));
        }
        try {
            ///AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            String newId = chatService.switchActiveAgentById(sessionUuid, agentTemplateId);
            if (newId == null) {
                return ResponseEntity.status(404)
                        .body(Map.of("status", "ERROR", "message", "Agent template not found"));
            }
            log.info("Agent switched via API [session={}, agentTemplateId={}]", sessionUuid, newId);
            return ResponseEntity.ok(Map.of("status", "OK", "agentTemplateId", newId));
        } catch (IllegalStateException ex) {
            log.warn("switchAgent denied [session={}, reason={}]", sessionUuid, ex.getMessage());
            return ResponseEntity.status(403)
                    .body(Map.of("status", "ERROR", "message", "Access denied"));
        }
    }

    @PostMapping("/session/{sessionUuid}/terminal/{terminalId}/terminate")
    public ResponseEntity<?> terminateCommand(@PathVariable String sessionUuid,
                                              @PathVariable String terminalId) {
        log.debug("ENTER terminateCommand: [session={}, terminal={}]", sessionUuid, terminalId);
        boolean sent = terminalStreamRouter.terminateActiveCommand(sessionUuid, terminalId);
        if (sent) {
            log.info("Terminal abort requested [session={}, terminal={}]", sessionUuid, terminalId);
            return ResponseEntity.ok(Map.of("status", "OK"));
        }
        log.warn("terminateCommand: no active command found [session={}, terminal={}]",
                sessionUuid, terminalId);
        return ResponseEntity.status(404)
                .body(Map.of("status", "NOT_FOUND", "message", "No active command for that terminal"));
    }

    @GetMapping("/agents")
    public List<AgentTemplateSummary> listAgents(
            @RequestParam(required = false) String type) {
        AgentType agentType = null;
        if (type != null && !type.isBlank()) {
            try {
                agentType = AgentType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                log.warn("listAgents: unknown type filter ignored [type={}]", type);
            }
        }

        return chatService.listAssignedAgentTemplatesForCurrentUser(agentType, true)
            .stream()
                .filter(t -> !t.hidden())
                .map(t -> new AgentTemplateSummary(t.uuid(), t.name(), t.agentType().name()))
                .toList();
    }

    @GetMapping("/welcome")
    public Map<String, String> getWelcomeMessage(
            @RequestParam(defaultValue = "GEMINI") String provider) {
        AiProvider aiProvider = resolveProvider(provider);
        // generateWelcomeMessage uses the active agent system prompt + a welcome
        // instruction suffix, with all tools stripped to prevent tool-auth challenges.
        // extractTextResponse unwraps the structured JSON response.
        String raw = aiOrchestrationService.generateWelcomeMessage(aiProvider);
        String content = chatService.extractTextResponse(raw);
        return Map.of("content", content != null ? content : "");
    }

    // ── Session extras: skills & tools ────────────────────────────────────────

    @PostMapping("/session/{sessionUuid}/session-skills/{skillUuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> addSessionSkill(@PathVariable String sessionUuid,
                                              @PathVariable String skillUuid) {
        log.debug("ENTER addSessionSkill: [session={}, skill={}]", sessionUuid, skillUuid);
        try {
            AiSession updated = chatService.addSessionSkill(sessionUuid, skillUuid);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionSkillUuids", updated.sessionSkillUuids()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/session/{sessionUuid}/session-skills/{skillUuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> removeSessionSkill(@PathVariable String sessionUuid,
                                                 @PathVariable String skillUuid) {
        log.debug("ENTER removeSessionSkill: [session={}, skill={}]", sessionUuid, skillUuid);
        try {
            AiSession updated = chatService.removeSessionSkill(sessionUuid, skillUuid);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionSkillUuids", updated.sessionSkillUuids()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @PostMapping("/session/{sessionUuid}/session-tools/{toolId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> addSessionTool(@PathVariable String sessionUuid,
                                             @PathVariable String toolId) {
        log.debug("ENTER addSessionTool: [session={}, tool={}]", sessionUuid, toolId);
        try {
            Reflection reflection = reflectionService.getReflectionById(toolId);
            if (reflection != null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "ERROR",
                        "message", "Reflections are not directly assignable tools. Assign reflection bindings instead."));
            }
            AiSession updated = chatService.addSessionTool(sessionUuid, toolId);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionToolIds", updated.sessionToolIds()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/session/{sessionUuid}/session-tools/{toolId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> removeSessionTool(@PathVariable String sessionUuid,
                                                @PathVariable String toolId) {
        log.debug("ENTER removeSessionTool: [session={}, tool={}]", sessionUuid, toolId);
        try {
            AiSession updated = chatService.removeSessionTool(sessionUuid, toolId);
            return ResponseEntity.ok(Map.of("status", "OK",
                    "sessionToolIds", updated.sessionToolIds()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @PostMapping("/session/{sessionUuid}/session-reflection-bindings/{bindingUuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> addSessionReflectionBinding(@PathVariable String sessionUuid,
                                                         @PathVariable String bindingUuid) {
        log.debug("ENTER addSessionReflectionBinding: [session={}, bindingUuid={}]", sessionUuid, bindingUuid);
        try {
            ReflectionBinding binding = reflectionService.getBindingByUuid(bindingUuid);
            if (binding == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "ERROR",
                        "message", "Unknown reflection binding UUID: " + bindingUuid));
            }
            AiSession updated = chatService.addSessionReflectionBinding(sessionUuid, bindingUuid);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "sessionReflectionBindingUuids", chatService.getSessionReflectionBindingUuids(updated)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/session/{sessionUuid}/session-reflection-bindings/{bindingUuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> removeSessionReflectionBinding(@PathVariable String sessionUuid,
                                                            @PathVariable String bindingUuid) {
        log.debug("ENTER removeSessionReflectionBinding: [session={}, bindingUuid={}]", sessionUuid, bindingUuid);
        try {
            AiSession updated = chatService.removeSessionReflectionBinding(sessionUuid, bindingUuid);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "sessionReflectionBindingUuids", chatService.getSessionReflectionBindingUuids(updated)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @PostMapping("/session/{sessionUuid}/session-mcp-bindings/{bindingUuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> addSessionMcpBinding(@PathVariable String sessionUuid,
                                                  @PathVariable String bindingUuid) {
        log.debug("ENTER addSessionMcpBinding: [session={}, bindingUuid={}]", sessionUuid, bindingUuid);
        try {
            var binding = mcpBindingService.get(bindingUuid);
            if (binding == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "ERROR",
                        "message", "Unknown MCP binding UUID: " + bindingUuid));
            }
            if (binding.status() != McpBindingStatus.ACTIVE) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "ERROR",
                        "message", "MCP binding must be ACTIVE before attaching to a session."));
            }
            AiSession updated = chatService.addSessionMcpBinding(sessionUuid, bindingUuid);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "sessionMcpBindingUuids", chatService.getSessionMcpBindingUuids(updated)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/session/{sessionUuid}/session-mcp-bindings/{bindingUuid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> removeSessionMcpBinding(@PathVariable String sessionUuid,
                                                     @PathVariable String bindingUuid) {
        log.debug("ENTER removeSessionMcpBinding: [session={}, bindingUuid={}]", sessionUuid, bindingUuid);
        try {
            AiSession updated = chatService.removeSessionMcpBinding(sessionUuid, bindingUuid);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "sessionMcpBindingUuids", chatService.getSessionMcpBindingUuids(updated)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @GetMapping("/reflection-bindings")
    public List<ReflectionBindingSummary> listReflectionBindings() {
        log.debug("ENTER listReflectionBindings");
        return reflectionService.listGroups().stream()
                .sorted(Comparator.comparing(g -> g.name() == null ? "" : g.name(), String.CASE_INSENSITIVE_ORDER))
                .flatMap(group -> reflectionService.bindingsForGroup(group.uuid()).stream()
                        .sorted(Comparator.comparing(b -> b.name() == null ? "" : b.name(), String.CASE_INSENSITIVE_ORDER))
                        .map(binding -> new ReflectionBindingSummary(
                                binding.uuid(),
                                group.uuid(),
                                group.name(),
                                binding.name(),
                                (group.name() == null ? group.uuid() : group.name()) + " (" + binding.name() + ")")))
                .toList();
    }

    @GetMapping("/bindings")
    public List<BindingSummary> listBindings() {
        log.debug("ENTER listBindings");
        return bindingCatalogService.listBindings();
    }

    @GetMapping("/mcp-bindings")
    public List<McpBindingSummary> listMcpBindings() {
        log.debug("ENTER listMcpBindingsForChat");
        return mcpBindingService.list().stream()
            .filter(b -> b.status() == McpBindingStatus.ACTIVE)
                .map(b -> new McpBindingSummary(
                        b.uuid(),
                        b.name(),
                b.status().name(),
                        b.baseUrl(),
                        mcpBindingService.listTools(b.uuid()).stream().filter(t -> t.enabled()).count(),
                        b.name() + " [MCP]"))
                .toList();
    }

    /** Returns all non-hidden tools from the registry, optionally filtered by category. */
    @GetMapping("/tools")
    public List<ToolSummary> listTools(
            @RequestParam(required = false) String category) {
        log.debug("ENTER listTools: [category={}]", category);
        java.util.Map<String, ToolSummary> merged = new java.util.LinkedHashMap<>();

        toolRegistry.getAvailableTools().stream()
                .filter(d -> category == null || category.isBlank() || d.category().equalsIgnoreCase(category))
                .map(d -> new ToolSummary(d.id(), d.friendlyName(), d.category(), d.description()))
            .forEach(summary -> merged.put(summary.id(), summary));

        return merged.values().stream()
            .sorted(Comparator.comparing(ToolSummary::category).thenComparing(ToolSummary::name))
            .toList();
    }

    /** Returns the agent config and session extras for the sidebar panel. */
    @GetMapping("/session/{sessionUuid}/agent-config")
    public ResponseEntity<?> getAgentConfig(@PathVariable String sessionUuid) {
        log.debug("ENTER getAgentConfig: [session={}]", sessionUuid);
        try {
            AiSession session = chatService.getSessionForCurrentUser(sessionUuid);
            // Agent skills
            sh.vork.ai.agent.AgentTemplate tpl = session.activeAgentTemplateId() != null
                    ? chatService.listAgentTemplates().stream()
                            .filter(t -> t.uuid().equals(session.activeAgentTemplateId()))
                            .findFirst().orElse(null)
                    : null;
            List<SkillSummary> agentSkills = tpl != null && tpl.skillUuids() != null
                    ? tpl.skillUuids().stream()
                            .map(skillRepo::get)
                            .filter(java.util.Objects::nonNull)
                            .map(s -> new SkillSummary(s.uuid(), s.name(), s.description(), s.toolName()))
                            .toList()
                    : List.of();
            // Session skills
            List<SkillSummary> sessionSkills = session.sessionSkillUuids().stream()
                    .map(skillRepo::get)
                    .filter(java.util.Objects::nonNull)
                    .map(s -> new SkillSummary(s.uuid(), s.name(), s.description(), s.toolName()))
                    .toList();
            // Agent tools (from allowedTools list)
            List<ToolSummary> agentTools = tpl != null && tpl.allowedTools() != null
                    ? tpl.allowedTools().stream()
                        .map(this::resolveToolSummaryById)
                            .filter(java.util.Objects::nonNull)
                            .toList()
                    : List.of();
                List<ReflectionBindingSummary> agentReflectionBindings = tpl != null
                    && tpl.bindingUuids() != null
                    ? tpl.bindingUuids().stream()
                    .distinct()
                    .map(reflectionService::getBindingByUuid)
                    .filter(java.util.Objects::nonNull)
                    .map(binding -> {
                    var group = reflectionService.getBindingGroup(binding);
                    String groupUuid = group != null ? group.uuid() : "";
                    String groupName = group != null ? group.name() : groupUuid;
                    String bindingName = binding.name() == null ? binding.uuid() : binding.name();
                    String label = (groupName == null ? groupUuid : groupName) + " (" + bindingName + ")";
                    return new ReflectionBindingSummary(
                        binding.uuid(),
                        groupUuid,
                        groupName,
                        bindingName,
                        label);
                    })
                    .toList()
                    : List.of();
            // Session tools
            List<ToolSummary> sessionTools = session.sessionToolIds().stream()
                    .map(this::resolveToolSummaryById)
                    .filter(java.util.Objects::nonNull)
                    .toList();
                boolean canManageSessionExtras = org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .getAuthentication()
                    .getAuthorities()
                    .stream()
                    .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
                List<ReflectionBindingSummary> sessionReflectionBindings = chatService
                    .getSessionReflectionBindingUuids(session)
                    .stream()
                    .map(reflectionService::getBindingByUuid)
                    .filter(java.util.Objects::nonNull)
                    .map(binding -> {
                    var group = reflectionService.getBindingGroup(binding);
                    String groupUuid = group != null ? group.uuid() : "";
                    String groupName = group != null ? group.name() : groupUuid;
                    String bindingName = binding.name() == null ? binding.uuid() : binding.name();
                    String label = (groupName == null ? groupUuid : groupName) + " (" + bindingName + ")";
                    return new ReflectionBindingSummary(
                        binding.uuid(),
                        groupUuid,
                        groupName,
                        bindingName,
                        label);
                    })
                    .toList();
                    List<McpBindingSummary> sessionMcpBindings = chatService
                        .getSessionMcpBindingUuids(session)
                        .stream()
                        .map(mcpBindingService::get)
                        .filter(java.util.Objects::nonNull)
                        .map(binding -> new McpBindingSummary(
                            binding.uuid(),
                            binding.name(),
                                binding.status().name(),
                            binding.baseUrl(),
                            mcpBindingService.listTools(binding.uuid()).stream().filter(t -> t.enabled()).count(),
                            binding.name() + " [MCP]"))
                        .toList();
            return ResponseEntity.ok(new AgentConfigResponse(
                    tpl != null ? tpl.uuid() : null,
                    tpl != null ? tpl.name() : null,
                    agentSkills,
                    sessionSkills,
                    agentTools,
                    sessionTools,
                    agentReflectionBindings,
                    sessionReflectionBindings,
                        sessionMcpBindings,
                    canManageSessionExtras));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(403).body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    // ── WebSocket / STOMP ─────────────────────────────────────────────────────

    @MessageMapping("/chat.send")
    public void handleChatMessage(ChatRequest request, java.security.Principal principal) {
        String sid = request == null ? null : request.sessionUuid();
        try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sid == null ? "<null>" : sid)) {
            log.debug("WebSocket message received [length={}, attachments={}]",
                request.content() == null ? 0 : request.content().length(),
                request.attachmentUuids() == null ? 0 : request.attachmentUuids().size());
            try {
            String username = (principal != null && principal.getName() != null) ? principal.getName() : "anonymous";
            AiProvider provider = resolveProviderOptional(request.provider());
            AiChatMessage response = chatService.sendMessageAsUser(
                username, request.sessionUuid(), request.content(), request.attachmentUuids(), provider);
            if (response != null) {
                messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), response);
            }
            } catch (Exception ex) {
            log.error("Chat error: {}", ex.getMessage(), ex);
            UiEventFrame frame = new UiEventFrame(
                UUID.randomUUID().toString(),
                "ERROR",
                "CHAT_ERROR",
                "Sorry, something went wrong: " + ex.getMessage(),
                null);
            messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), frame);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider resolveProvider(String name) {
        if (name == null || name.isBlank()) return AiProvider.GEMINI;
        try {
            return AiProvider.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown provider '{}', defaulting to GEMINI", name);
            return AiProvider.GEMINI;
        }
    }

    private static AiProvider resolveProviderOptional(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return AiProvider.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown provider '{}' in chat request, deferring to runtime defaults", name);
            return null;
        }
    }

    private void persistRequestBaseUrl(String sessionUuid, HttpServletRequest request) {
        if (sessionUuid == null || sessionUuid.isBlank() || request == null) {
            return;
        }

        String baseUrl = RequestOriginContext.resolveBaseUrl(request);
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }

        sessionEnvironmentService.setEnv(sessionUuid, "__request_base_url__", baseUrl);

        Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
        String existingRedirectUri = env.get("redirectUri");
        if (existingRedirectUri == null || existingRedirectUri.isBlank() || isUnresolvedRedirectUri(existingRedirectUri)) {
            sessionEnvironmentService.setEnv(sessionUuid, "redirectUri", baseUrl + "/api/oauth/callback");
        }
    }

    private static boolean isUnresolvedRedirectUri(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.contains("<your_ip_address>")
                || (normalized.contains("<") && normalized.contains(">"));
    }

    private static boolean isChildCampaignSession(AiSession session) {
        if (session == null || session.environmentVariables() == null) {
            return false;
        }
        String campaignId = session.environmentVariables().get("REQUEST_CAMPAIGN_ID");
        String routeMode = session.environmentVariables().get("REQUEST_CAMPAIGN_ROUTE_MODE");
        return campaignId != null && !campaignId.isBlank()
                && routeMode != null && "CHILD_SESSION".equalsIgnoreCase(routeMode);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record SessionResponse(String sessionUuid, String sessionName, String provider,
                            String activeAgentTemplateId, List<AiChatMessage> messages, String modelId,
                            AiSessionStatus status, String originMode, boolean requestCampaignChildSession) {}

    record SessionMessagesResponse(String sessionUuid,
                                   List<AiChatMessage> messages,
                                   AiSessionStatus status,
                                   boolean requestCampaignChildSession) {}

    record SessionSummaryResponse(String sessionUuid, String sessionName, String provider,
                                  long createdAt, int messageCount, String modelId,
                                  String activeAgentTemplateId) {}

    record RenameSessionRequest(String name) {}

    record CampaignResponseRequest(String message) {}

    record ChatRequest(String sessionUuid, String content, String provider, List<String> attachmentUuids) {}

    record AgentTemplateSummary(String uuid, String name, String agentType) {}

    record SkillSummary(String uuid, String name, String description, String toolName) {}

    record ToolSummary(String id, String name, String category, String description) {}

    record ReflectionBindingSummary(String uuid,
                                    String groupUuid,
                                    String groupName,
                                    String bindingName,
                                    String label) {}

    record McpBindingSummary(String uuid,
                             String name,
                             String status,
                             String baseUrl,
                             long toolCount,
                             String label) {}

    private ToolSummary resolveToolSummaryById(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }

        ToolSummary fromRegistry = toolRegistry.getAvailableTools().stream()
                .filter(d -> d.id().equals(toolId))
                .findFirst()
                .map(d -> new ToolSummary(d.id(), d.friendlyName(), d.category(), d.description()))
                .orElse(null);
        if (fromRegistry != null) {
            return fromRegistry;
        }

        return null;
    }

    record AgentConfigResponse(
            String agentUuid,
            String agentName,
            List<SkillSummary> agentSkills,
            List<SkillSummary> sessionSkills,
            List<ToolSummary> agentTools,
            List<ToolSummary> sessionTools,
            List<ReflectionBindingSummary> agentReflectionBindings,
            List<ReflectionBindingSummary> sessionReflectionBindings,
            List<McpBindingSummary> sessionMcpBindings,
            boolean canManageSessionExtras) {}
}
