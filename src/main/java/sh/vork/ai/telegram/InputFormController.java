package sh.vork.ai.telegram;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.orm.DatabaseRepository;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.request.RequestInformationService;
import sh.vork.notification.telegram.TelegramApiClient;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.scheduling.service.SystemBackgroundAuthentication;

/**
 * Generic web-form controller for suspended AI sessions.
 *
 * <p>Handles the web-form leg of any tool-suspension flow — both Telegram-originated
 * sessions (where the Telegram bot sent the URL) and background-job sessions (where the
 * operator received an email/log notification with the URL).
 *
 * <h3>URL pattern</h3>
 * <pre>{@code GET /input-form/{sessionUuid}/{eventId}?token=...}</pre>
 * <pre>{@code POST /input-form/{sessionUuid}/{eventId}?token=...}</pre>
 *
 * <p>The token is single-use and expires after 15 minutes.  Access is permitted without
 * a login session (token acts as one-time credential).
 *
 * <h3>Post-submission behaviour by session origin</h3>
 * <ul>
 *   <li><b>TELEGRAM</b> — full AI continuation loop runs; result is sent back via the bot.</li>
 *   <li><b>BACKGROUND</b> — fields are processed, the tool is executed, and
 *       {@link AiSchedulerService#resumeBackgroundSession(String)} is kicked off on
 *       the background executor.  The web page shows a "processing" confirmation.</li>
 *   <li><b>WEB / other</b> — same as TELEGRAM (falls through to AI continuation).</li>
 * </ul>
 */
@Controller
@RequestMapping("/input-form")
public class InputFormController {

    private static final Logger log = LoggerFactory.getLogger(InputFormController.class);

    private final InputFormTokenService         formTokenService;
    private final TelegramChatResumptionService resumptionService;
    private final DatabaseRepository<AiSession> sessionRepo;
    private final TelegramApiClient             telegramApiClient;
    private final AiSchedulerService            aiSchedulerService;
    private final Executor                      aiBackgroundExecutor;
    private final ObjectMapper                  objectMapper;
    private final RequestInformationService     requestInformationService;
    private final SimpMessagingTemplate         messaging;

    public InputFormController(InputFormTokenService formTokenService,
                                TelegramChatResumptionService resumptionService,
                                DatabaseRepository<AiSession> sessionRepo,
                                TelegramApiClient telegramApiClient,
                                AiSchedulerService aiSchedulerService,
                                @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor,
                                ObjectMapper objectMapper,
                                RequestInformationService requestInformationService,
                                SimpMessagingTemplate messaging) {
        this.formTokenService    = formTokenService;
        this.resumptionService   = resumptionService;
        this.sessionRepo         = sessionRepo;
        this.telegramApiClient   = telegramApiClient;
        this.aiSchedulerService  = aiSchedulerService;
        this.aiBackgroundExecutor = aiBackgroundExecutor;
        this.objectMapper        = objectMapper;
        this.requestInformationService = requestInformationService;
        this.messaging = messaging;
    }

    // ── GET — render form ─────────────────────────────────────────────────────

    @GetMapping("/{sessionUuid}/{eventId}")
    public String showForm(@PathVariable String sessionUuid,
                            @PathVariable String eventId,
                            @RequestParam String token,
                            Model model) {

        log.debug("ENTER showForm: session={}, event={}", sessionUuid, eventId);

        InputFormTokenService.TokenClaims claims = formTokenService.validateToken(token);
        if (claims == null) {
            log.warn("Invalid or expired token for input form [session={}, event={}]",
                    sessionUuid, eventId);
            model.addAttribute("errorMessage", "This link is invalid or has expired.");
            return "input-form-error";
        }
        if (!sessionUuid.equals(claims.sessionUuid()) || !eventId.equals(claims.eventId())) {
            log.warn("Token/session mismatch for input form [pathSession={}, pathEvent={}, claimSession={}, claimEvent={}]",
                sessionUuid, eventId, claims.sessionUuid(), claims.eventId());
            model.addAttribute("errorMessage", "This link does not match the pending prompt.");
            return "input-form-error";
        }

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null || session.status() != AiSessionStatus.AWAITING_INPUT) {
            model.addAttribute("errorMessage", "This prompt is no longer active.");
            return "input-form-error";
        }

        UiEventFrame promptEvent = findPromptEvent(session, eventId);
        if (promptEvent == null) {
            model.addAttribute("errorMessage", "Could not locate the pending prompt.");
            return "input-form-error";
        }

        InteractionFormSchema schema = promptEvent.formSchema();
        List<FormField> visibleFields = schema == null || schema.fields() == null
                ? List.of()
                : schema.fields().stream()
                        .filter(f -> f != null && !isInvisibleType(f.type()))
                        .collect(Collectors.toList());

        model.addAttribute("sessionUuid",  sessionUuid);
        model.addAttribute("eventId",      eventId);
        model.addAttribute("token",        token);
        model.addAttribute("title",        schema != null ? schema.title() : "Action required");
        model.addAttribute("description",  promptEvent.textResponse());
        model.addAttribute("fields",       visibleFields);
        model.addAttribute("actions",      schema != null && schema.actions() != null
                ? schema.actions() : List.of());

        boolean isBackground = session.originMode() == SessionOriginMode.BACKGROUND;
        model.addAttribute("isBackground", isBackground);

        log.debug("EXIT showForm: rendering form with {} field(s), origin={}",
                visibleFields.size(), session.originMode());
        return "input-form";
    }

    // ── POST — submit form ────────────────────────────────────────────────────

    @PostMapping("/{sessionUuid}/{eventId}")
    public String submitForm(@PathVariable String sessionUuid,
                              @PathVariable String eventId,
                              @RequestParam String token,
                              @RequestParam Map<String, String> params,
                              Model model) {

        log.debug("ENTER submitForm: session={}, event={}", sessionUuid, eventId);

        InputFormTokenService.TokenClaims claims = formTokenService.validateToken(token);
        if (claims == null) {
            model.addAttribute("errorMessage", "This link is invalid or has expired.");
            return "input-form-error";
        }
        if (!sessionUuid.equals(claims.sessionUuid()) || !eventId.equals(claims.eventId())) {
            log.warn("Token/session mismatch on submit [pathSession={}, pathEvent={}, claimSession={}, claimEvent={}]",
                    sessionUuid, eventId, claims.sessionUuid(), claims.eventId());
            model.addAttribute("errorMessage", "This link does not match the pending prompt.");
            return "input-form-error";
        }

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null || session.status() != AiSessionStatus.AWAITING_INPUT) {
            model.addAttribute("errorMessage", "This prompt is no longer active.");
            return "input-form-error";
        }

        UiEventFrame promptEvent = findPromptEvent(session, eventId);
        if (promptEvent == null) {
            model.addAttribute("errorMessage", "Could not locate the pending prompt.");
            return "input-form-error";
        }

        String validationError = validateRequiredFields(promptEvent.formSchema(), params);
        if (validationError != null) {
            prepareFormModel(model, sessionUuid, eventId, token, promptEvent, validationError, params);
            return "input-form";
        }

        // Extract the chosen action (submitted as a button's name/value)
        String action = params.getOrDefault("action", params.getOrDefault("_action", "ONCE"));

        // Build field map — strip reserved params
        Map<String, String> fields = params.entrySet().stream()
            .filter(e -> !e.getKey().startsWith("_")
                && !"token".equals(e.getKey())
                && !"action".equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        fields.replaceAll((k, v) -> v == null ? "" : v.trim());

        // Consume token only after validation so users can correct invalid input.
        formTokenService.consumeToken(token);

        String actingUsername = claims.username();

        if (claims.requestCampaignUuid() != null && !claims.requestCampaignUuid().isBlank()) {
            String responderChannel = claims.responderChannel() != null && !claims.responderChannel().isBlank()
                    ? claims.responderChannel()
                    : claims.username();

            RequestInformationService.ResponseGateResult gate =
                    requestInformationService.recordResponseAndEvaluate(
                            claims.requestCampaignUuid(),
                            responderChannel,
                            action,
                            fields);

            if (!gate.accepted()) {
                model.addAttribute("message", gate.userMessage());
                return "input-form-done";
            }

            if (!gate.shouldResume()) {
                model.addAttribute("message", gate.userMessage());
                return "input-form-done";
            }

            if (!requestInformationService.markResumeStarted(claims.requestCampaignUuid())) {
                model.addAttribute("message", "Thanks. Required responses were already received and processing has resumed.");
                return "input-form-done";
            }

            fields = requestInformationService.buildResumeFields(claims.requestCampaignUuid());
            action = "ONCE";
            actingUsername = session.username();
        }

        SessionOriginMode origin = session.originMode();

        try {
            if (origin == SessionOriginMode.BACKGROUND) {
                return handleBackgroundSubmit(claims, sessionUuid, eventId, action, fields, actingUsername, model);
            } else {
                return handleInteractiveSubmit(claims, sessionUuid, eventId, action, fields, actingUsername, session, model);
            }
        } catch (Exception ex) {
            log.warn("Error submitting input form [session={}]: {}", sessionUuid, ex.getMessage(), ex);
            model.addAttribute("errorMessage", "An error occurred while processing your response.");
            return "input-form-error";
        }
    }

    // ── Submission strategies ─────────────────────────────────────────────────

    /**
     * Handles form submission for background-origin sessions.
     * Processes fields + executes the tool, then resumes the background engine on its
     * dedicated executor thread pool.
     */
    private String handleBackgroundSubmit(InputFormTokenService.TokenClaims claims,
                                           String sessionUuid, String eventId,
                                           String action, Map<String, String> fields,
                                           String actingUsername,
                                           Model model) {
        log.info("Background form submit [session={}, user={}]", sessionUuid, actingUsername);
        try {
            // Process fields + execute tool; saves session as RUNNING so the engine can pick up
            resumptionService.processAndActivate(
                    actingUsername, sessionUuid, eventId, action, fields);

        } catch (ToolSuspensionException ex) {
            // Tool suspended immediately again — we still started the engine; it will handle it
            log.info("Tool re-suspended during background form submit [session={}]", sessionUuid);
        }

        // Kick off the background engine on its isolated thread pool (mirrors ChatAuthorizationController)
        String username = actingUsername;
        aiBackgroundExecutor.execute(() -> {
            ToolExecutionContext.bindSessionUuid(sessionUuid);
            AiSession fresh = sessionRepo.get(sessionUuid);
            if (fresh != null) ToolExecutionContext.hydrate(fresh.environmentVariables());
            try {
                SecurityContextHolder.getContext()
                        .setAuthentication(new SystemBackgroundAuthentication(username));
                aiSchedulerService.resumeBackgroundSession(sessionUuid);
            } catch (Exception ex) {
                log.error("Background resume failed after form submit [session={}]: {}",
                        sessionUuid, ex.getMessage(), ex);
            } finally {
                SecurityContextHolder.clearContext();
                ToolExecutionContext.clear();
            }
        });

        model.addAttribute("message",
                "Your response was submitted. The background task is resuming — "
                + "check the Jobs panel for progress.");
        log.info("Background session re-activated via input form [session={}]", sessionUuid);
        return "input-form-done";
    }

    /**
     * Handles form submission for interactive-origin sessions (TELEGRAM, WEB, etc.).
     * Runs the full AI continuation loop and, for TELEGRAM sessions, sends the reply via bot.
     */
    private String handleInteractiveSubmit(InputFormTokenService.TokenClaims claims,
                                            String sessionUuid, String eventId,
                                            String action, Map<String, String> fields,
                                            String actingUsername,
                                            AiSession session, Model model) {
        try {
            String result = resumptionService.resumeAndRun(
                    actingUsername, sessionUuid, eventId, action, fields);

            if (session.originMode() == SessionOriginMode.TELEGRAM) {
                String chatId   = session.environmentVariables().get("TELEGRAM_CHAT_ID");
                String botToken = session.environmentVariables().get("TELEGRAM_BOT_TOKEN");
                if (chatId != null && botToken != null && result != null && !result.isBlank()) {
                    telegramApiClient.sendText(botToken, chatId, result);
                }
            }

            if (result != null && !result.isBlank()) {
                messaging.convertAndSend("/topic/chat/" + sessionUuid,
                        new UiEventFrame(java.util.UUID.randomUUID().toString(), "TEXT_RESPONSE", "CHAT_OUTPUT",
                                result, null));
            }

            model.addAttribute("message", "Your response was submitted successfully. "
                    + (session.originMode() == SessionOriginMode.TELEGRAM
                            ? "Check Telegram for the reply."
                            : "The AI session has continued."));
            log.info("Input form submitted [session={}, user={}, origin={}]",
                    sessionUuid, actingUsername, session.originMode());
            return "input-form-done";

        } catch (ToolSuspensionException ex) {
            // Another suspension — push latest prompt to active web subscribers.
            AiSession fresh = sessionRepo.get(sessionUuid);
            UiEventFrame nextPrompt = fresh == null ? null : findPromptEvent(fresh, null);
            if (nextPrompt != null) {
                messaging.convertAndSend("/topic/chat/" + sessionUuid, nextPrompt);
            }
            model.addAttribute("message", "Your response was submitted. "
                    + "Another confirmation may be required — please check for a new prompt.");
            return "input-form-done";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UiEventFrame findPromptEvent(AiSession session, String eventId) {
        var messages = session.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var m = messages.get(i);
            if ("PROMPT_REQUIRED".equals(m.role())) {
                try {
                    UiEventFrame frame = objectMapper.readValue(m.content(), UiEventFrame.class);
                    if (eventId == null || eventId.equals(frame.eventId())) return frame;
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private static boolean isInvisibleType(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return "HIDDEN".equals(t) || "MARKDOWN".equals(t);
    }

    private String validateRequiredFields(InteractionFormSchema schema, Map<String, String> params) {
        if (schema == null || schema.fields() == null) {
            return null;
        }

        for (FormField field : schema.fields()) {
            if (field == null || !field.required() || isInvisibleType(field.type())) {
                continue;
            }
            if ("READONLY".equalsIgnoreCase(field.type())) {
                continue;
            }

            String value = params.get(field.name());
            if (value == null || value.trim().isBlank()) {
                String label = (field.label() == null || field.label().isBlank())
                        ? field.name()
                        : field.label();
                return "Please provide a value for: " + label;
            }
        }

        return null;
    }

    private void prepareFormModel(Model model,
                                  String sessionUuid,
                                  String eventId,
                                  String token,
                                  UiEventFrame promptEvent,
                                  String errorMessage,
                                  Map<String, String> submittedValues) {
        InteractionFormSchema schema = promptEvent.formSchema();

        List<FormField> visibleFields = schema == null || schema.fields() == null
                ? List.of()
                : schema.fields().stream()
                        .filter(f -> f != null && !isInvisibleType(f.type()))
                        .map(f -> {
                            String submittedValue = submittedValues == null ? null : submittedValues.get(f.name());
                            return new FormField(
                                    f.name(),
                                    f.type(),
                                    f.label(),
                                    f.placeholder(),
                                    submittedValue != null ? submittedValue : f.value(),
                                    f.required(),
                                    f.source(),
                                    f.options());
                        })
                        .collect(Collectors.toList());

        model.addAttribute("sessionUuid", sessionUuid);
        model.addAttribute("eventId", eventId);
        model.addAttribute("token", token);
        model.addAttribute("title", schema != null ? schema.title() : "Action required");
        model.addAttribute("description", promptEvent.textResponse());
        model.addAttribute("fields", visibleFields);
        model.addAttribute("actions", schema != null && schema.actions() != null
                ? schema.actions() : List.of());
        model.addAttribute("errorMessage", errorMessage);
    }
}
