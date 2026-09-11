package sh.vork.surface.service;

import org.junit.jupiter.api.Test;
import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.service.ChatService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SurfaceAgentExecutionServiceTest {

    @Test
    void startAndPoll_completesWithResponseObject() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceAgentExecutionService service = new SurfaceAgentExecutionService(surfaceService, chatService);

        AgentTemplate template = new AgentTemplate(
                "agent-1",
                "Surface Agent",
                "",
                List.of(),
                false,
                List.of(),
                AgentType.SURFACE);

        AiSession executionSession = new AiSession(
                "exec-session",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Exec",
                1L,
                0,
                List.of(),
                null,
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of());

        when(surfaceService.resolveAttachedSurfaceAgent("surface-1", "agent-1")).thenReturn(template);
        when(surfaceService.ensureExecutionSession("surface-1", "admin")).thenReturn(executionSession);
        when(chatService.switchActiveAgentById("exec-session", "agent-1")).thenReturn("agent-1");
        when(chatService.sendMessage(eq("exec-session"), anyString(), eq(List.of()), eq(AiProvider.GEMINI), eq(false)))
                .thenReturn(new AiChatMessage(
                        "m-1",
                        "ASSISTANT",
                        "{\"responseObject\":{\"city\":\"Paris\"}}",
                        System.currentTimeMillis(),
                        null));

        SurfaceAgentExecutionService.ExecutionSnapshot started = service.start(
                "surface-1",
                "admin",
                "agent-1",
                "Return a city",
                Map.of(
                        "type", "object",
                        "required", List.of("city"),
                        "properties", Map.of("city", Map.of("type", "string")),
                        "additionalProperties", false));

        SurfaceAgentExecutionService.ExecutionSnapshot done = service.poll("surface-1", started.executionId(), 2000L);

        assertEquals(SurfaceAgentExecutionService.ExecutionState.COMPLETED, done.state());
        assertEquals("application/json", done.outputContentType());
        Map<?, ?> result = (Map<?, ?>) done.result();
        assertEquals("Paris", result.get("city"));
        assertNotNull(done.textResponse());
    }

    @Test
    void startAndPoll_failsWhenSchemaDoesNotMatch() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceAgentExecutionService service = new SurfaceAgentExecutionService(surfaceService, chatService);

        AgentTemplate template = new AgentTemplate(
                "agent-1",
                "Surface Agent",
                "",
                List.of(),
                false,
                List.of(),
                AgentType.SURFACE);

        AiSession executionSession = new AiSession(
                "exec-session",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Exec",
                1L,
                0,
                List.of(),
                null,
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of());

        when(surfaceService.resolveAttachedSurfaceAgent("surface-1", "agent-1")).thenReturn(template);
        when(surfaceService.ensureExecutionSession("surface-1", "admin")).thenReturn(executionSession);
        when(chatService.switchActiveAgentById("exec-session", "agent-1")).thenReturn("agent-1");
        when(chatService.sendMessage(eq("exec-session"), anyString(), eq(List.of()), eq(AiProvider.GEMINI), eq(false)))
                .thenReturn(new AiChatMessage(
                        "m-1",
                        "ASSISTANT",
                        "{\"responseObject\":{\"city\":123}}",
                        System.currentTimeMillis(),
                        null));

        SurfaceAgentExecutionService.ExecutionSnapshot started = service.start(
                "surface-1",
                "admin",
                "agent-1",
                "Return a city",
                Map.of(
                        "type", "object",
                        "required", List.of("city"),
                        "properties", Map.of("city", Map.of("type", "string")),
                        "additionalProperties", false));

        SurfaceAgentExecutionService.ExecutionSnapshot done = service.poll("surface-1", started.executionId(), 2000L);

        assertEquals(SurfaceAgentExecutionService.ExecutionState.FAILED, done.state());
        assertNotNull(done.error());
    }
}
