package sh.vork.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.agent.ArtifactStatus;
import sh.vork.ai.service.AgentAssignmentService;
import sh.vork.binding.BindingCatalogService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionService;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.skill.Skill;

class AgentControllerTest {

    @Test
    void createAgent_rejectsDuplicateName_caseInsensitive() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
        AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(
                new AgentTemplate("a1", "Triage Agent", "", List.of(), false, List.of(), AgentType.BACKGROUND))
                .stream());

        AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
        AgentController.AgentRequest req = new AgentController.AgentRequest(
                "triage agent", "", List.of(), List.of(), AgentType.BACKGROUND, List.of(), List.of(), List.of(), null,
                "vork", "triageAgent");

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
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
        AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

        AgentTemplate existing = new AgentTemplate("a2", "Support Agent", "", List.of(), false, List.of(), AgentType.INTERACTIVE);
        when(agentRepo.get("a2")).thenReturn(existing);
        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(
                existing,
                new AgentTemplate("a1", "Triage Agent", "", List.of(), false, List.of(), AgentType.BACKGROUND))
                .stream());

        AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
        AgentController.AgentRequest req = new AgentController.AgentRequest(
                "Triage Agent", "", List.of(), List.of(), AgentType.INTERACTIVE, List.of(), List.of(), List.of(), null,
                "vork", "supportAgent");

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
                DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                ReflectionService reflectionService = mock(ReflectionService.class);
                BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
                AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

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
                                "application/json",
                                "",
                                1L,
                                System.currentTimeMillis(),
                                System.currentTimeMillis()));

                AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
                AgentController.AgentRequest req = new AgentController.AgentRequest(
                                "triage agent", "", List.of("reflection-tool-id"), List.of(), AgentType.BACKGROUND, List.of(), List.of(), List.of(), null,
                                "vork", "triageAgent");

                ResponseEntity<?> response = controller.createAgent(req);

                assertEquals(400, response.getStatusCode().value());
                Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
                assertEquals(true, String.valueOf(body.get("error")).contains("Reflections are not directly assignable tools"));
                verify(agentRepo, never()).save(org.mockito.ArgumentMatchers.any());
        }

    @Test
    void createAgent_usesDeterministicVid() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
        AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.<AgentTemplate>of().stream());
        when(agentRepo.get("vork-vorkDeveloper-SNAPSHOT")).thenReturn(null);

        AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
        AgentController.AgentRequest req = new AgentController.AgentRequest(
                "Vork Developer", "", List.of(), List.of(), AgentType.INTERACTIVE,
                List.of(), List.of(), List.of(), null, "vork", "vorkDeveloper");

        ResponseEntity<?> response = controller.createAgent(req);

        assertEquals(200, response.getStatusCode().value());
        AgentTemplate created = assertInstanceOf(AgentTemplate.class, response.getBody());
                assertEquals("vork-vorkDeveloper-SNAPSHOT", created.uuid());
        assertEquals("vork", created.groupId());
                assertEquals("vorkDeveloper", created.artifactId());
        assertEquals("SNAPSHOT", created.version());
        assertEquals(ArtifactStatus.SNAPSHOT, created.artifactStatus());
    }

    @Test
    void updateAgent_rejectsSystemAgent() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
        AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

        AgentTemplate system = new AgentTemplate("agent-tpl-concierge-001", "Concierge", "", List.of(), true,
                List.of(), AgentType.INTERACTIVE, List.of(), List.of(), List.of(), null, null, null, null, null);
        when(agentRepo.get("agent-tpl-concierge-001")).thenReturn(system);

        AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
        AgentController.AgentRequest req = new AgentController.AgentRequest(
                "Concierge", "new prompt", List.of(), List.of(), AgentType.INTERACTIVE,
                List.of(), List.of(), List.of(), null, "vork", "concierge");

        ResponseEntity<?> response = controller.updateAgent("agent-tpl-concierge-001", req);
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void exportAgent_rejectsSystemAgent() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
        AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

        AgentTemplate system = new AgentTemplate("agent-tpl-concierge-001", "Concierge", "", List.of(), true,
                List.of(), AgentType.INTERACTIVE, List.of(), List.of(), List.of(), null, null, null, null, null);
        when(agentRepo.get("agent-tpl-concierge-001")).thenReturn(system);

        AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
        ResponseEntity<?> response = controller.exportAgent("agent-tpl-concierge-001");

        assertEquals(403, response.getStatusCode().value());
    }

        @Test
        void updateAgent_rejectsNonSnapshotAgent() {
                @SuppressWarnings("unchecked")
                DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                ReflectionService reflectionService = mock(ReflectionService.class);
                BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
                AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

                AgentTemplate existing = new AgentTemplate(
                                "vork-ops-PUBLISHED",
                                "Ops Agent",
                                "",
                                List.of(),
                                false,
                                List.of(),
                                AgentType.INTERACTIVE,
                                List.of(),
                                List.of(),
                                null,
                                "vork",
                                "ops",
                                "SNAPSHOT",
                                ArtifactStatus.PUBLISHED);
                when(agentRepo.get("vork-ops-PUBLISHED")).thenReturn(existing);

                AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
                AgentController.AgentRequest req = new AgentController.AgentRequest(
                                "Ops Agent", "new prompt", List.of(), List.of(), AgentType.INTERACTIVE,
                                List.of(), List.of(), List.of(), null, "vork", "ops");

                ResponseEntity<?> response = controller.updateAgent("vork-ops-PUBLISHED", req);
                assertEquals(403, response.getStatusCode().value());
        }

        @Test
        void deleteAgent_rejectsNonSnapshotAgent() {
                @SuppressWarnings("unchecked")
                DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                ReflectionService reflectionService = mock(ReflectionService.class);
                BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
                AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

                AgentTemplate existing = new AgentTemplate(
                                "vork-ops-PUBLISHED",
                                "Ops Agent",
                                "",
                                List.of(),
                                false,
                                List.of(),
                                AgentType.INTERACTIVE,
                                List.of(),
                                List.of(),
                                null,
                                "vork",
                                "ops",
                                "SNAPSHOT",
                                ArtifactStatus.PUBLISHED);
                when(agentRepo.get("vork-ops-PUBLISHED")).thenReturn(existing);

                AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
                ResponseEntity<?> response = controller.deleteAgent("vork-ops-PUBLISHED");

                assertEquals(403, response.getStatusCode().value());
                verify(agentRepo, never()).delete("vork-ops-PUBLISHED");
        }

        @Test
        void importAgent_rejectsNonSnapshotStatus() {
                @SuppressWarnings("unchecked")
                DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                ReflectionService reflectionService = mock(ReflectionService.class);
                BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
                AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

                when(agentRepo.get("vork-ops-SNAPSHOT")).thenReturn(null);
                when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.<AgentTemplate>of().stream());

                AgentTemplate incoming = new AgentTemplate(
                                "vork-ops-SNAPSHOT",
                                "Ops Agent",
                                "",
                                List.of(),
                                false,
                                List.of(),
                                AgentType.INTERACTIVE,
                                List.of(),
                                List.of(),
                                null,
                                "vork",
                                "ops",
                                "SNAPSHOT",
                                ArtifactStatus.PUBLISHED);

                AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
                ResponseEntity<?> response = controller.importAgent(new AgentController.AgentExportPackage("1.0", incoming));

                assertEquals(400, response.getStatusCode().value());
                AgentController.AgentImportResult result = assertInstanceOf(AgentController.AgentImportResult.class, response.getBody());
                assertTrue(result.message().contains("Only SNAPSHOT"));
        }

        @Test
        void createAgent_rejectsAssignedJobsWithoutDelegateTask() {
                @SuppressWarnings("unchecked")
                DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                ReflectionService reflectionService = mock(ReflectionService.class);
                BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
                AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

                when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.<AgentTemplate>of().stream());
                ScheduledJob job = new ScheduledJob(
                                "vork-dailySummary-SNAPSHOT",
                                "Daily Summary",
                                "Prompt",
                                null,
                                "user",
                                InvocationType.MANUAL,
                                Instant.now(),
                                0L,
                                DurationType.MINUTES,
                                0L,
                                0L,
                                null,
                                null,
                                null,
                                240,
                                null,
                                ScheduledJobStatus.WAITING,
                                List.of(),
                                List.of(),
                                List.of(),
                                "vork",
                                "dailySummary",
                                "SNAPSHOT",
                                sh.vork.scheduling.domain.ArtifactStatus.SNAPSHOT);
                when(jobRepo.get("vork-dailySummary-SNAPSHOT")).thenReturn(job);

                AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
                AgentController.AgentRequest req = new AgentController.AgentRequest(
                                "Delegate Agent", "Instructions", List.of(), List.of(), AgentType.BACKGROUND,
                                List.of(), List.of(), List.of("vork-dailySummary-SNAPSHOT"), null, "vork", "delegateAgent");

                ResponseEntity<?> response = controller.createAgent(req);

                assertEquals(400, response.getStatusCode().value());
                Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
                assertTrue(String.valueOf(body.get("error")).contains("delegateTask"));
        }

        @Test
        void createAgent_rejectsMultipleJobVersionsForSameArtifact() {
                @SuppressWarnings("unchecked")
                DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                ReflectionService reflectionService = mock(ReflectionService.class);
                BindingCatalogService bindingCatalogService = mock(BindingCatalogService.class);
                AgentAssignmentService agentAssignmentService = mock(AgentAssignmentService.class);

                when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.<AgentTemplate>of().stream());

                ScheduledJob v1 = new ScheduledJob(
                                "vork-report-SNAPSHOT",
                                "Report v1",
                                "Prompt",
                                null,
                                "user",
                                InvocationType.MANUAL,
                                Instant.now(),
                                0L,
                                DurationType.MINUTES,
                                0L,
                                0L,
                                null,
                                null,
                                null,
                                240,
                                null,
                                ScheduledJobStatus.WAITING,
                                List.of(),
                                List.of(),
                                List.of(),
                                "vork",
                                "report",
                                "SNAPSHOT",
                                sh.vork.scheduling.domain.ArtifactStatus.SNAPSHOT);

                ScheduledJob v2 = new ScheduledJob(
                                "vork-report-v2",
                                "Report v2",
                                "Prompt",
                                null,
                                "user",
                                InvocationType.MANUAL,
                                Instant.now(),
                                0L,
                                DurationType.MINUTES,
                                0L,
                                0L,
                                null,
                                null,
                                null,
                                240,
                                null,
                                ScheduledJobStatus.WAITING,
                                List.of(),
                                List.of(),
                                List.of(),
                                "vork",
                                "report",
                                "v2",
                                sh.vork.scheduling.domain.ArtifactStatus.SNAPSHOT);

                when(jobRepo.get("vork-report-SNAPSHOT")).thenReturn(v1);
                when(jobRepo.get("vork-report-v2")).thenReturn(v2);

                AgentController controller = new AgentController(agentRepo, jobRepo, skillRepo, reflectionService, bindingCatalogService, agentAssignmentService);
                AgentController.AgentRequest req = new AgentController.AgentRequest(
                                "Delegate Agent", "Use delegateTask only for assigned jobs.", List.of("delegateTask"), List.of(), AgentType.BACKGROUND,
                                List.of(), List.of(), List.of("vork-report-SNAPSHOT", "vork-report-v2"), null, "vork", "delegateAgent");

                ResponseEntity<?> response = controller.createAgent(req);

                assertEquals(400, response.getStatusCode().value());
                Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
                assertTrue(String.valueOf(body.get("error")).contains("Only one job version"));
        }
}
