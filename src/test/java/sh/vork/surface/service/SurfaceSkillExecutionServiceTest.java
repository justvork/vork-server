package sh.vork.surface.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import sh.vork.ai.AiProvider;
import sh.vork.ai.service.ChatService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillActivatedException;
import sh.vork.skill.SkillService;
import sh.vork.skill.SkillVisibility;

class SurfaceSkillExecutionServiceTest {

    @Test
    void start_andPoll_completesWithJsonResult() {
        SkillService skillService = mock(SkillService.class);
        ChatService chatService = mock(ChatService.class);

        Skill skill = new Skill(
                "skill-1",
                "Skill One",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                java.util.List.of(),
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                "application/json",
                "{\"type\":\"object\"}",
                1L,
                1L,
                1L,
                java.util.List.of(),
                java.util.List.of());

        when(skillService.executeSkill(eq("skill-1"), anyMap()))
                .thenReturn("{\"status\":\"ok\",\"value\":42}");

        SurfaceSkillExecutionService service = new SurfaceSkillExecutionService(skillService, chatService);

        SurfaceSkillExecutionService.ExecutionSnapshot started = service.start(
                "surface-1",
                "session-1",
                skill,
                Map.of("a", "b"),
                AiProvider.GEMINI);

        SurfaceSkillExecutionService.ExecutionSnapshot done = service.poll("surface-1", started.executionId(), 2000L);

        assertEquals(SurfaceSkillExecutionService.ExecutionState.COMPLETED, done.state());
        assertEquals("application/json", done.outputContentType());
        assertNotNull(done.result());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) done.result();
        assertEquals("ok", String.valueOf(payload.get("status")));
        assertEquals(42, ((Number) payload.get("value")).intValue());
    }

    @Test
    void start_andPoll_failsWhenJsonContractButOutputIsNotJson() {
        SkillService skillService = mock(SkillService.class);
        ChatService chatService = mock(ChatService.class);

        Skill skill = new Skill(
                "skill-2",
                "Skill Two",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                java.util.List.of(),
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                "application/json",
                "{\"type\":\"object\"}",
                1L,
                1L,
                1L,
                java.util.List.of(),
                java.util.List.of());

        when(skillService.executeSkill(eq("skill-2"), anyMap()))
                .thenReturn("not-json");

        SurfaceSkillExecutionService service = new SurfaceSkillExecutionService(skillService, chatService);

        SurfaceSkillExecutionService.ExecutionSnapshot started = service.start(
                "surface-1",
                "session-1",
                skill,
                Map.of(),
                AiProvider.GEMINI);

        SurfaceSkillExecutionService.ExecutionSnapshot done = service.poll("surface-1", started.executionId(), 2000L);

        assertEquals(SurfaceSkillExecutionService.ExecutionState.FAILED, done.state());
        assertNotNull(done.error());
        assertTrue(done.error().contains("non-JSON output"));
    }

    @Test
    void poll_timeoutReturnsCurrentNonTerminalState() throws Exception {
        SkillService skillService = mock(SkillService.class);
        ChatService chatService = mock(ChatService.class);

        Skill skill = new Skill(
                "skill-3",
                "Skill Three",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                java.util.List.of(),
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                "application/json",
                "{\"type\":\"object\"}",
                1L,
                1L,
                1L,
                java.util.List.of(),
                java.util.List.of());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(skillService.executeSkill(eq("skill-3"), anyMap())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "{\"status\":\"ok\"}";
        });

        SurfaceSkillExecutionService service = new SurfaceSkillExecutionService(skillService, chatService);

        SurfaceSkillExecutionService.ExecutionSnapshot started = service.start(
                "surface-1",
                "session-1",
                skill,
                Map.of(),
                AiProvider.GEMINI);

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        SurfaceSkillExecutionService.ExecutionSnapshot polled = service.poll("surface-1", started.executionId(), 1L);

        assertTrue(polled.state() == SurfaceSkillExecutionService.ExecutionState.PENDING
                || polled.state() == SurfaceSkillExecutionService.ExecutionState.RUNNING);

        release.countDown();
        SurfaceSkillExecutionService.ExecutionSnapshot done = service.poll("surface-1", started.executionId(), 2000L);
        assertEquals(SurfaceSkillExecutionService.ExecutionState.COMPLETED, done.state());
    }

    @Test
    void start_andPoll_delegatesToChatSubLoop_whenSkillActivatedExceptionIsThrown() {
        SkillService skillService = mock(SkillService.class);
        ChatService chatService = mock(ChatService.class);

        Skill skill = new Skill(
                "skill-4",
                "Skill Four",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                java.util.List.of(),
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                "application/json",
                "{\"type\":\"object\"}",
                1L,
                1L,
                1L,
                java.util.List.of(),
                java.util.List.of());

        when(skillService.executeSkill(eq("skill-4"), anyMap()))
                .thenThrow(new SkillActivatedException("skill-4", "Skill Four", "initial prompt"));
        when(chatService.executeSkillSubLoop(eq("exec-session-4"), eq(java.util.List.of()), org.mockito.ArgumentMatchers.any(SkillActivatedException.class), eq(AiProvider.GEMINI)))
                .thenReturn("{\"status\":\"ok\",\"value\":7}");

        SurfaceSkillExecutionService service = new SurfaceSkillExecutionService(skillService, chatService);

        SurfaceSkillExecutionService.ExecutionSnapshot started = service.start(
                "surface-1",
                "exec-session-4",
                skill,
                Map.of(),
                AiProvider.GEMINI);

        SurfaceSkillExecutionService.ExecutionSnapshot done = service.poll("surface-1", started.executionId(), 2000L);

        assertEquals(SurfaceSkillExecutionService.ExecutionState.COMPLETED, done.state());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) done.result();
        assertEquals(7, ((Number) payload.get("value")).intValue());
    }
}
