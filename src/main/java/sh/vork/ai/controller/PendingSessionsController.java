package sh.vork.ai.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;

/**
 * Provides a session-agnostic view of all AI sessions that are currently
 * {@link AiSessionStatus#AWAITING_INPUT} for the authenticated user.
 *
 * <p>Covers both {@code TELEGRAM} and {@code BACKGROUND} origin sessions so the
 * user can supply the required input directly from the web UI without needing a
 * relay token or a Telegram interaction.
 *
 * <p>Submission is handled by the existing
 * {@code POST /api/chat/respond/{sessionUuid}} endpoint — the same one used by
 * the chat authorization card flow — which routes correctly based on origin mode.
 */
@Controller
public class PendingSessionsController {

    private static final Logger log = LoggerFactory.getLogger(PendingSessionsController.class);

    private final DatabaseRepository<AiSession> sessionRepo;
    private final ObjectMapper objectMapper;

    public PendingSessionsController(DatabaseRepository<AiSession> sessionRepo,
                                     ObjectMapper objectMapper) {
        this.sessionRepo   = sessionRepo;
        this.objectMapper  = objectMapper;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/pending-sessions")
    public String pendingSessionsPage() {
        log.debug("ENTER pendingSessionsPage");
        return "pending-sessions";
    }

    // ── REST: list pending-input sessions ─────────────────────────────────────

    /**
     * Returns all {@link AiSessionStatus#AWAITING_INPUT} sessions for the current
     * user that originated from Telegram or a background job.
     */
    @GetMapping("/api/chat/sessions/pending-input")
    @ResponseBody
    public List<PendingSessionSummary> pendingInputSessions() {
        String username = resolveUsername();
        log.debug("ENTER pendingInputSessions: user={}", username);

        List<PendingSessionSummary> result = new ArrayList<>();

        try (var stream = sessionRepo.search(
                0, 100, "createdAt", SortOrder.DESC,
                SearchQuery.eq("username", username),
                SearchQuery.eq("status", AiSessionStatus.AWAITING_INPUT.name()),
                SearchQuery.in("originMode", "TELEGRAM", "BACKGROUND"))) {

            stream.forEach(session -> {
                AiChatMessage promptMessage = findLastPromptMessage(session.messages());
                if (promptMessage == null) return;

                try {
                    UiEventFrame frame = objectMapper.readValue(
                            promptMessage.content(), UiEventFrame.class);
                    if (frame == null) return;

                    result.add(new PendingSessionSummary(
                            session.uuid(),
                            session.name(),
                            session.originMode().name(),
                            session.createdAt(),
                            frame.eventId(),
                            promptMessage.toolName(),
                            frame.textResponse(),
                            frame.formSchema(),
                            promptMessage.toolCallId()
                    ));
                } catch (Exception ex) {
                    log.debug("Skipping session — failed to parse prompt event [session={}, error={}]",
                            session.uuid(), ex.getMessage());
                }
            });
        }

        log.debug("EXIT pendingInputSessions: found {} pending session(s) for user={}", result.size(), username);
        return result;
    }

        /**
         * Dismisses a stale pending-input request by transitioning the session out of
         * {@link AiSessionStatus#AWAITING_INPUT}. This removes it from the pending list.
         */
        @DeleteMapping("/api/chat/sessions/pending-input/{sessionUuid}")
        @ResponseBody
        public ResponseEntity<Map<String, Object>> dismissPendingInputSession(@PathVariable String sessionUuid) {
        String username = resolveUsername();
        log.debug("ENTER dismissPendingInputSession: user={}, session={}", username, sessionUuid);

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            log.warn("dismissPendingInputSession: session not found [session={}]", sessionUuid);
            return ResponseEntity.status(404).body(Map.of(
                "status", "NOT_FOUND",
                "message", "Session not found"));
        }
        if (!username.equals(session.username())) {
            log.warn("dismissPendingInputSession: access denied [session={}, user={}]", sessionUuid, username);
            return ResponseEntity.status(403).body(Map.of(
                "status", "FORBIDDEN",
                "message", "Access denied"));
        }
        if (session.status() != AiSessionStatus.AWAITING_INPUT) {
            log.warn("dismissPendingInputSession: session not awaiting input [session={}, status={}]",
                sessionUuid, session.status());
            return ResponseEntity.status(409).body(Map.of(
                "status", "NOT_AWAITING_INPUT",
                "message", "Session is not waiting for input"));
        }

        List<AiChatMessage> updatedMessages = new ArrayList<>(session.messages());
        updatedMessages.add(new AiChatMessage(
            UUID.randomUUID().toString(),
            "ASSISTANT",
            "Pending input request dismissed by user.",
            System.currentTimeMillis(),
            null));

        AiSession updated = new AiSession(
            session.uuid(),
            session.provider(),
            session.originMode(),
            session.username(),
            session.name(),
            session.createdAt(),
            session.currentRoundCount(),
            List.copyOf(updatedMessages),
            session.environmentVariables(),
            AiSessionStatus.COMPLETED,
            session.activeAgentTemplateId(),
            session.modelId(),
            session.skillStack(),
            session.sessionSkillUuids(),
            session.sessionToolIds());
        sessionRepo.save(updated);

        log.info("EXIT dismissPendingInputSession: dismissed [session={}, user={}]", sessionUuid, username);
        return ResponseEntity.ok(Map.of(
            "status", "DISMISSED",
            "sessionUuid", sessionUuid));
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiChatMessage findLastPromptMessage(List<AiChatMessage> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiChatMessage msg = messages.get(i);
            if ("PROMPT_REQUIRED".equals(msg.role())) {
                return msg;
            }
        }
        return null;
    }

    private static String resolveUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    /**
     * Serializable summary of a single session waiting for user input.
     *
     * @param sessionUuid  the AI session UUID (used as the resume endpoint path variable)
     * @param sessionName  human-friendly session name
     * @param originMode   {@code TELEGRAM} or {@code BACKGROUND}
     * @param createdAt    epoch-milliseconds when the session was created
     * @param eventId      the pending prompt event ID (needed in the resume request)
     * @param toolName     the name of the tool waiting for authorisation
     * @param reasoning    plain-text explanation provided by the AI (may be null)
     * @param formSchema   the full {@link InteractionFormSchema} for rendering the form
     * @param toolCallId   the tool call ID associated with this prompt
     */
    public record PendingSessionSummary(
            String sessionUuid,
            String sessionName,
            String originMode,
            long createdAt,
            String eventId,
            String toolName,
            String reasoning,
            InteractionFormSchema formSchema,
            String toolCallId
    ) {}
}
