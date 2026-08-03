package sh.vork.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionService;
import sh.vork.skill.Skill;

class AgentControllerTest {

    @Test
    void createAgent_rejectsDuplicateName_caseInsensitive() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);

        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(
                new AgentTemplate("a1", "Triage Agent", "", List.of(), false, List.of(), AgentType.BACKGROUND))
                .stream());

        AgentController controller = new AgentController(agentRepo, skillRepo, reflectionService);
        AgentController.AgentRequest req = new AgentController.AgentRequest(
                "triage agent", "", List.of(), List.of(), AgentType.BACKGROUND, List.of());

        ResponseEntity<?> response = controller.createAgent(req);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("Agent name already exists.", body.get("error"));
        verify(agentRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateAgent_rejectsDuplicateNameFromAnotherAgent() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);

        AgentTemplate existing = new AgentTemplate("a2", "Support Agent", "", List.of(), false, List.of(), AgentType.INTERACTIVE);
        when(agentRepo.get("a2")).thenReturn(existing);
        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(
                existing,
                new AgentTemplate("a1", "Triage Agent", "", List.of(), false, List.of(), AgentType.BACKGROUND))
                .stream());

        AgentController controller = new AgentController(agentRepo, skillRepo, reflectionService);
        AgentController.AgentRequest req = new AgentController.AgentRequest(
                "Triage Agent", "", List.of(), List.of(), AgentType.INTERACTIVE, List.of());

        ResponseEntity<?> response = controller.updateAgent("a2", req);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("Agent name already exists.", body.get("error"));
        verify(agentRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

        @Test
        void createAgent_rejectsDirectReflectionToolId() {
                @SuppressWarnings("unchecked")
                DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                ReflectionService reflectionService = mock(ReflectionService.class);

                when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.<AgentTemplate>of().stream());
                when(reflectionService.getReflectionById("reflection-tool-id")).thenReturn(new Reflection(
                                "r-uuid",
                                "reflection-tool-id",
                                "Reflection Tool",
                                "desc",
                                "group-1",
                                List.of(),
                                "GET",
                                "https://example.com",
                                Map.of(),
                                Map.of(),
                                "",
                                "application/json",
                                1L,
                                System.currentTimeMillis(),
                                System.currentTimeMillis()));

                AgentController controller = new AgentController(agentRepo, skillRepo, reflectionService);
                AgentController.AgentRequest req = new AgentController.AgentRequest(
                                "triage agent", "", List.of("reflection-tool-id"), List.of(), AgentType.BACKGROUND, List.of());

                ResponseEntity<?> response = controller.createAgent(req);

                assertEquals(400, response.getStatusCode().value());
                Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
                assertEquals(true, String.valueOf(body.get("error")).contains("Reflections are not directly assignable tools"));
                verify(agentRepo, never()).save(org.mockito.ArgumentMatchers.any());
        }
}
