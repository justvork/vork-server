package sh.vork.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.DatabaseRepository;

class ReflectionToolCallbackFactoryTest {

    private ReflectionService reflectionService;
    private DatabaseRepository<AiSession> aiSessionRepository;
    private ReflectionToolCallbackFactory factory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reflectionService = mock(ReflectionService.class);
        aiSessionRepository = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);

        factory = new ReflectionToolCallbackFactory(new ObjectMapper());
        ReflectionTestUtils.setField(factory, "reflectionService", reflectionService);
        ReflectionTestUtils.setField(factory, "aiSessionRepository", aiSessionRepository);
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void usesReflectionIdAsToolName() {
        Reflection reflection = sampleReflection();
        ToolCallback callback = factory.create(reflection);

        assertEquals("getWeather", callback.getToolDefinition().name());
    }

    @Test
    void returnsMissingParametersWhenRequiredFieldsAbsent() {
        Reflection reflection = sampleReflection();
        ToolCallback callback = factory.create(reflection);

        String result = callback.call("{}");

        assertTrue(result.contains("missing_parameters"));
        assertTrue(result.contains("city"));
    }

    @Test
    void delegatesExecutionWithSessionUsername() {
        Reflection reflection = sampleReflection();
        ToolCallback callback = factory.create(reflection);

        ToolExecutionContext.bindSessionUuid("session-1");
        when(aiSessionRepository.get("session-1")).thenReturn(new AiSession(
                "session-1",
                "GEMINI",
                SessionOriginMode.WEB,
                "alice",
                "Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(),
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of()));
        when(reflectionService.executeRestReflection(eq("getWeather"), any(), eq("alice")))
                .thenReturn("{\"status\":\"ok\"}");

        String result = callback.call("{\"city\":\"London\"}");

        assertEquals("{\"status\":\"ok\"}", result);
        verify(reflectionService).executeRestReflection(eq("getWeather"), any(), eq("alice"));
    }

    private static Reflection sampleReflection() {
        return new Reflection(
                "uuid-1",
                "getWeather",
                "Get Weather",
                "desc",
                "group-1",
                List.of(new ReflectionInputParameter("city", "string", "City", true)),
                "GET",
                "https://example.com/weather",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis());
    }
}
