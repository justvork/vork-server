package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import sh.vork.orm.DatabaseRepository;

import sh.vork.ai.AiProvider;
import sh.vork.ai.config.AiConfig;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.skill.SkillFrame;

class AiOrchestrationServicePromptHydrationTest {

    @BeforeEach
    void initThreadLocalContext() {
        ToolExecutionContext.clear();
    }

    @AfterEach
    void clearThreadLocalContext() {
        ToolExecutionContext.clear();
    }

    @Test
    void composeSystemPrompt_whenNoSessionBound_returnsBasePromptOnly() throws Exception {
        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
            Map.of(), null, envService, mock(DatabaseRepository.class), mock(DatabaseRepository.class), null, Map.of(), null, null, null, null, null, null, null);

        String prompt = invokeComposeSystemPrompt(service);

        assertEquals(AiConfig.BASE_SYSTEM_PROMPT, prompt);
    }

    @Test
    void composeSystemPrompt_whenSessionBoundButEnvEmpty_returnsBasePromptOnly() throws Exception {
        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        when(envService.getEnv("session-1")).thenReturn(Map.of());
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        when(sessionRepo.get("session-1")).thenReturn(null);

        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
                Map.of(AiProvider.GEMINI, mock(ChatClient.class)), null, envService,
            sessionRepo, mock(DatabaseRepository.class), null, Map.of(), null, null, null, null, null, null, null);
        ToolExecutionContext.bindSessionUuid("session-1");

        String prompt = invokeComposeSystemPrompt(service);

        assertTrue(prompt.startsWith(AiConfig.BASE_SYSTEM_PROMPT));
        assertFalse(prompt.contains("### ACTIVE SESSION ENVIRONMENT VARIABLES"));
        verify(envService).getEnv("session-1");
    }

    @Test
    void composeSystemPrompt_whenEnvPresent_appendsEnvHeaderAndKeyValueLines() throws Exception {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("activeTargetAnchor", "local");
        env.put("selectedProfile", "prod");

        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        when(envService.getEnv("session-2")).thenReturn(env);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        when(sessionRepo.get("session-2")).thenReturn(null);

        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
                Map.of(AiProvider.GEMINI, mock(ChatClient.class)), null, envService,
            sessionRepo, mock(DatabaseRepository.class), null, Map.of(), null, null, null, null, null, null, null);
        ToolExecutionContext.bindSessionUuid("session-2");

        String prompt = invokeComposeSystemPrompt(service);

        assertTrue(prompt.startsWith(AiConfig.BASE_SYSTEM_PROMPT));
        assertTrue(prompt.contains("### ACTIVE SESSION ENVIRONMENT VARIABLES"));
        assertTrue(prompt.contains("activeTargetAnchor=local"));
        assertTrue(prompt.contains("selectedProfile=prod"));
        verify(envService).getEnv("session-2");
    }

    @Test
    void composeSystemPrompt_whenEnvInsertionIsUnordered_sortsEnvironmentKeysAlphabetically() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("zeta", "3");
        env.put("alpha", "1");
        env.put("middle", "2");

        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        when(envService.getEnv("session-3")).thenReturn(env);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        when(sessionRepo.get("session-3")).thenReturn(null);

        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
                Map.of(AiProvider.GEMINI, mock(ChatClient.class)), null, envService,
                sessionRepo, mock(DatabaseRepository.class), null, Map.of(), null, null, null, null, null, null, null);
        ToolExecutionContext.bindSessionUuid("session-3");

        String prompt = invokeComposeSystemPrompt(service);

        int alpha = prompt.indexOf("alpha=1");
        int middle = prompt.indexOf("middle=2");
        int zeta = prompt.indexOf("zeta=3");
        assertTrue(alpha >= 0);
        assertTrue(middle > alpha);
        assertTrue(zeta > middle);
        verify(envService).getEnv("session-3");
    }

        @Test
        void composeSystemPrompt_whenInSkillFrame_stillIncludesEnvironmentVariablesBlock() throws Exception {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("active_target_alias", "db-main");

        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);
        when(envService.getEnv("session-skill-1")).thenReturn(env);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AiSession session = new AiSession(
            "session-skill-1",
            "GEMINI",
            SessionOriginMode.WEB,
            "tester",
            "Skill Session",
            System.currentTimeMillis(),
            0,
            List.of(),
            Map.of(),
            AiSessionStatus.RUNNING,
            null,
            null,
            List.of(new SkillFrame(
                "skill-1",
                "Skill One",
                "Do the task",
                List.of(),
                List.of(),
                Map.of(),
                0)),
            List.of(),
            List.of());
        when(sessionRepo.get("session-skill-1")).thenReturn(session);

        @SuppressWarnings("unchecked")
        DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);
        when(skillRepo.get("skill-1")).thenReturn(null);

        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
            Map.of(AiProvider.GEMINI, mock(ChatClient.class)),
            null,
            envService,
            sessionRepo,
            mock(DatabaseRepository.class),
            skillRepo,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
        ToolExecutionContext.bindSessionUuid("session-skill-1");

        String prompt = invokeComposeSystemPrompt(service);

        assertTrue(prompt.contains("### ACTIVE SESSION ENVIRONMENT VARIABLES"));
        assertTrue(prompt.contains("active_target_alias=db-main"));
        verify(envService).getEnv("session-skill-1");
        }

    @Test
    void resolveFilteredToolCallbacks_whenSkillFrameHasAllowedTypes_autoInjectsTypeCrudToolsOnly() throws Exception {
        SessionEnvironmentService envService = mock(SessionEnvironmentService.class);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AiSession session = new AiSession(
            "session-skill-tools-1",
            "GEMINI",
            SessionOriginMode.WEB,
            "tester",
            "Skill Tools Session",
            System.currentTimeMillis(),
            0,
            List.of(),
            Map.of(),
            AiSessionStatus.RUNNING,
            null,
            null,
            List.of(new SkillFrame(
                "skill-tools-1",
                "Skill Tools",
                "Do type work",
                List.of(),
                List.of("sh.vork.generated.CustomerRecord"),
                Map.of(),
                0)),
            List.of(),
            List.of());
        when(sessionRepo.get("session-skill-tools-1")).thenReturn(session);

        @SuppressWarnings("unchecked")
        DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);
        when(skillRepo.get("skill-tools-1")).thenReturn(null);

        Map<String, ToolCallback> securedToolMap = Map.of(
                "getTypeSchema", namedTool("getTypeSchema"),
                "saveTypeInstance", namedTool("saveTypeInstance"),
                "getTypeInstance", namedTool("getTypeInstance"),
                "listTypeInstances", namedTool("listTypeInstances"),
                "countTypeInstances", namedTool("countTypeInstances"),
                "deleteTypeInstance", namedTool("deleteTypeInstance"),
                "searchTypeInstances", namedTool("searchTypeInstances"),
                "listEnumValues", namedTool("listEnumValues"),
                "compileJavaType", namedTool("compileJavaType"));

        @SuppressWarnings("unchecked")
        AiOrchestrationService service = new AiOrchestrationService(
            Map.of(AiProvider.GEMINI, mock(ChatClient.class)),
            null,
            envService,
            sessionRepo,
            mock(DatabaseRepository.class),
            skillRepo,
            securedToolMap,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
        ToolExecutionContext.bindSessionUuid("session-skill-tools-1");

        ToolCallback[] resolved = invokeResolveFilteredToolCallbacks(service);
        List<String> names = java.util.Arrays.stream(resolved)
                .map(cb -> cb.getToolDefinition().name())
                .toList();

        assertTrue(names.contains("getTypeSchema"));
        assertTrue(names.contains("saveTypeInstance"));
        assertTrue(names.contains("searchTypeInstances"));
        assertFalse(names.contains("compileJavaType"));
    }

    private static String invokeComposeSystemPrompt(AiOrchestrationService service) throws Exception {
        Method compose = AiOrchestrationService.class.getDeclaredMethod("composeSystemPrompt");
        compose.setAccessible(true);
        return (String) compose.invoke(service);
    }

    private static ToolCallback[] invokeResolveFilteredToolCallbacks(AiOrchestrationService service) throws Exception {
        Method resolve = AiOrchestrationService.class.getDeclaredMethod("resolveFilteredToolCallbacks");
        resolve.setAccessible(true);
        return (ToolCallback[]) resolve.invoke(service);
    }

    private static ToolCallback namedTool(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
