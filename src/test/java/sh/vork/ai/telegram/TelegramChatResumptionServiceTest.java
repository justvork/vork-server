package sh.vork.ai.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.security.SecureCredentialStore;
import sh.vork.security.UserService;
import sh.vork.security.VorkUser;

class TelegramChatResumptionServiceTest {

    @org.junit.jupiter.api.AfterEach
    void clearToolContext() {
        ToolExecutionContext.clear();
    }

    @Test
    void resumeAndRun_collectSkillInputSave_mergesConversationFieldsIntoToolArgs() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        String sessionUuid = "session-skill-input";
        String eventId = "event-skill-input";
        String toolCallId = "pending-skill-input";

        UiEventFrame promptFrame = new UiEventFrame(
                eventId,
                "PROMPT_REQUIRED",
                "COLLECT_SKILL_INPUT",
                "Confirm input",
                new InteractionFormSchema(
                        "COLLECT_SKILL_INPUT",
                        "Skill Input",
                        "Confirm field values",
                        List.of(new FormField("query", "text", "Query", "", true,
                                FieldSource.CONVERSATION, List.of())),
                        List.of(new FormAction("SAVE", "Save & Continue", "primary"))));

        AiChatMessage promptMessage = new AiChatMessage(
                "msg-prompt",
                "PROMPT_REQUIRED",
                objectMapper.writeValueAsString(promptFrame),
                System.currentTimeMillis(),
                null,
                List.of(new AiChatMessage.ToolCallRef(toolCallId, "FUNCTION", "skillTool",
                        "{\"query\":\"ai-default\"}")),
                toolCallId,
                "skillTool");

        AiSession session = new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.TELEGRAM,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(promptMessage),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT,
                null,
                null,
                null,
                null,
                null);
        sessionRepo.save(session);

        SessionEnvironmentService sessionEnvironmentService = mock(SessionEnvironmentService.class);
        SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
        AuthorizationRuleEngine authorizationRuleEngine = new AuthorizationRuleEngine(java.util.Set.of());
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        ChatService chatService = mock(ChatService.class);
        UserService userService = mock(UserService.class);

        when(userService.getRequiredEnabledUser("alice"))
                .thenReturn(new VorkUser("alice", "hash", "USER", true, 0L, 0L));
        when(aiService.generateWithHistory(anyList(), anyString(), any()))
                .thenReturn("{\"status\":\"FINISHED_TURN\",\"textResponse\":\"done\"}");

        AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();
        ToolCallback tool = new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name("skillTool")
                    .description("skill tool")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                try {
                    capturedArgs.set(objectMapper.readValue(toolInput,
                            new TypeReference<Map<String, Object>>() {}));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return "{\"status\":\"ok\"}";
            }
        };

        TelegramChatResumptionService service = new TelegramChatResumptionService(
                sessionRepo,
                sessionEnvironmentService,
                secureCredentialStore,
                authorizationRuleEngine,
                aiService,
                chatService,
                userService,
                objectMapper,
                List.of(tool));

        String result = service.resumeAndRun(
                "alice",
                sessionUuid,
                eventId,
                "SAVE",
                Map.of("query", "user-confirmed"));

        assertEquals("done", result);
        assertNotNull(capturedArgs.get());
        assertEquals("user-confirmed", String.valueOf(capturedArgs.get().get("query")));
    }
}
