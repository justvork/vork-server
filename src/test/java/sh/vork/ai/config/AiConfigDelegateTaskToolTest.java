package sh.vork.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.service.AgentAssignmentService;
import sh.vork.ai.security.InToolAuthorizationService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

class AiConfigDelegateTaskToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        org.slf4j.MDC.clear();
        ToolExecutionContext.clear();
    }

    @Test
    void delegateTask_schedulesDynamicBackgroundJobFromAgentNameAndPrompt() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiSchedulerService> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerService);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AgentAssignmentService assignmentService = mock(AgentAssignmentService.class);
        InToolAuthorizationService authorizationService = mock(InToolAuthorizationService.class);

        AgentTemplate triage = new AgentTemplate(
                "agent-1",
                "Triage Agent",
                "",
                List.of(),
                false,
                List.of(),
                AgentType.BACKGROUND,
                List.of(),
                List.of(),
                List.of("job-template-1"),
                null);

            ScheduledJob assignedJob = new ScheduledJob(
                "job-template-1",
                "Assigned Template",
                "Original prompt",
                null,
                "alice",
                InvocationType.MANUAL,
                java.time.Instant.now(),
                0L,
                sh.vork.scheduling.domain.DurationType.MINUTES,
                0L,
                0L,
                null,
                null,
                null,
                240,
                null,
                sh.vork.scheduling.domain.ScheduledJobStatus.WAITING,
                List.of(),
                List.of(),
                List.of(),
                "vork",
                "assignedTemplate",
                "SNAPSHOT",
                sh.vork.artifact.ArtifactStatus.SNAPSHOT);

        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(triage).stream());
            when(jobRepo.get("job-template-1")).thenReturn(assignedJob);
        when(assignmentService.isAssignedToUser(eq(triage), eq("alice"))).thenReturn(true);
        when(schedulerService.scheduleDelegatedDynamicJob(any(ScheduledJob.class), nullable(String.class), anyList())).thenAnswer(invocation -> {
            ScheduledJob in = invocation.getArgument(0);
            return new ScheduledJob(
                    "job-123",
                    in.name(),
                    in.aiPrompt(),
                    in.sessionUuid(),
                    in.userId(),
                    in.invocationType(),
                    in.startTime(),
                    in.repeatDuration(),
                    in.durationType(),
                    in.lastExecutionTime(),
                    in.nextExecutionTime(),
                    in.agentTemplateId(),
                    in.provider(),
                    in.modelId(),
                    in.oobTimeoutMinutes(),
                    in.expectedOutput(),
                    in.status(),
                    in.skillUuids(),
                    in.toolIds());
        });

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.delegateTask(schedulerProvider, agentRepo, jobRepo, sessionRepo, assignmentService, authorizationService);

        String output = tool.call("{\"agentName\":\"Triage Agent\",\"jobUuid\":\"job-template-1\",\"prompt\":\"Classify this content\"}");
        Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});

        assertEquals("scheduled", result.get("status"));
        assertEquals("job-123", result.get("jobId"));
        assertEquals("Triage Agent", result.get("agentName"));

        ArgumentCaptor<ScheduledJob> jobCaptor = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(schedulerService).scheduleDelegatedDynamicJob(jobCaptor.capture(), isNull(), anyList());
        ScheduledJob created = jobCaptor.getValue();
        assertEquals("alice", created.userId());
        assertEquals("Classify this content", created.aiPrompt());
        assertEquals("agent-1", created.agentTemplateId());
        assertEquals(InvocationType.DYNAMIC, created.invocationType());
        assertTrue(created.name().startsWith("Dynamic: "));
    }

    @Test
    void delegateTask_createsDynamicJobWhenJobUuidIsOmitted() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiSchedulerService> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerService);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AgentAssignmentService assignmentService = mock(AgentAssignmentService.class);
        InToolAuthorizationService authorizationService = mock(InToolAuthorizationService.class);

        AgentTemplate triage = new AgentTemplate(
                "agent-1",
                "Triage Agent",
                "",
                List.of("delegateTask"),
                false,
                List.of("skill-1"),
                AgentType.BACKGROUND,
                List.of(),
                List.of(),
                List.of(),
                "GEMINI:gemini-2.5-flash");

        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(triage).stream());
        when(assignmentService.isAssignedToUser(eq(triage), eq("alice"))).thenReturn(true);
        when(schedulerService.scheduleDelegatedDynamicJob(any(ScheduledJob.class), nullable(String.class), anyList()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.delegateTask(schedulerProvider, agentRepo, jobRepo, sessionRepo, assignmentService, authorizationService);

        String output = tool.call("{\"agentName\":\"Triage Agent\",\"prompt\":\"Classify this content\"}");
        Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});

        assertEquals("scheduled", result.get("status"));
        assertEquals("DYNAMIC", result.get("invocationType"));
        assertTrue(result.containsKey("jobId"));

        ArgumentCaptor<ScheduledJob> jobCaptor = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(schedulerService).scheduleDelegatedDynamicJob(jobCaptor.capture(), isNull(), anyList());
        ScheduledJob created = jobCaptor.getValue();
        assertEquals("alice", created.userId());
        assertEquals("Classify this content", created.aiPrompt());
        assertEquals("agent-1", created.agentTemplateId());
        assertEquals(InvocationType.DYNAMIC, created.invocationType());
        assertEquals("GEMINI", created.provider());
        assertEquals("gemini-2.5-flash", created.modelId());
    }

        @Test
        void delegateTask_passesSessionFilesIntoDynamicSchedulerCall() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiSchedulerService> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerService);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AgentAssignmentService assignmentService = mock(AgentAssignmentService.class);
        InToolAuthorizationService authorizationService = mock(InToolAuthorizationService.class);

        AgentTemplate triage = new AgentTemplate(
            "agent-1",
            "Triage Agent",
            "",
            List.of("delegateTask"),
            false,
            List.of("skill-1"),
            AgentType.BACKGROUND,
            List.of(),
            List.of(),
            List.of(),
            "GEMINI:gemini-2.5-flash");

        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(triage).stream());
        when(assignmentService.isAssignedToUser(eq(triage), eq("alice"))).thenReturn(true);
        when(schedulerService.scheduleDelegatedDynamicJob(any(ScheduledJob.class), anyString(), anyList()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", "n/a"));
        org.slf4j.MDC.put("sessionUuid", "session-parent-1");

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.delegateTask(schedulerProvider, agentRepo, jobRepo, sessionRepo, assignmentService, authorizationService);

        String output = tool.call("{" +
            "\"agentName\":\"Triage Agent\"," +
            "\"prompt\":\"Classify this content\"," +
            "\"sessionFiles\":[\"inbox/mail-1.txt\",\"inbox/mail-1.txt\",\"/api/session-files/download?area=SESSION&path=docs%2Fbrief.md\"]" +
            "}");
        Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});

        assertEquals("scheduled", result.get("status"));
        assertEquals(2, result.get("copiedSessionFileCount"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(schedulerService).scheduleDelegatedDynamicJob(any(ScheduledJob.class), eq("session-parent-1"), filesCaptor.capture());
        assertEquals(List.of("inbox/mail-1.txt", "/api/session-files/download?area=SESSION&path=docs%2Fbrief.md"), filesCaptor.getValue());
        }

        @Test
        void delegateTask_rejectsSessionFilesInSystemContext() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiSchedulerService> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerService);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AgentAssignmentService assignmentService = mock(AgentAssignmentService.class);
        InToolAuthorizationService authorizationService = mock(InToolAuthorizationService.class);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", "n/a"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.delegateTask(schedulerProvider, agentRepo, jobRepo, sessionRepo, assignmentService, authorizationService);

        String output = tool.call("{" +
            "\"agentName\":\"Triage Agent\"," +
            "\"prompt\":\"Classify this content\"," +
            "\"sessionFiles\":[\"inbox/mail-1.txt\"]" +
            "}");
        Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});

        assertEquals("error", result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("sessionFiles require a non-system session context"));
        verify(schedulerService, never()).scheduleDelegatedDynamicJob(any(ScheduledJob.class), anyString(), anyList());
        }

    @Test
    void delegateTask_returnsAmbiguousErrorWhenAgentNameMatchesMultiple() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiSchedulerService> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerService);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AgentAssignmentService assignmentService = mock(AgentAssignmentService.class);
        InToolAuthorizationService authorizationService = mock(InToolAuthorizationService.class);

        AgentTemplate a = new AgentTemplate("agent-a", "Triage Agent", "", List.of(), false, List.of(), AgentType.BACKGROUND);
        AgentTemplate b = new AgentTemplate("agent-b", "Triage Agent", "", List.of(), false, List.of(), AgentType.BACKGROUND);
        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(a, b).stream());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.delegateTask(schedulerProvider, agentRepo, jobRepo, sessionRepo, assignmentService, authorizationService);

        String output = tool.call("{\"agentName\":\"Triage Agent\",\"jobUuid\":\"job-template-1\",\"prompt\":\"Classify this content\"}");
        Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});

        assertEquals("error", result.get("status"));
        assertEquals("Triage Agent", result.get("agentName"));
        assertTrue(result.containsKey("candidates"));
    }

    @Test
    void delegateTask_rejectsInteractiveAgent() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiSchedulerService> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerService);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AgentAssignmentService assignmentService = mock(AgentAssignmentService.class);
        InToolAuthorizationService authorizationService = mock(InToolAuthorizationService.class);

        AgentTemplate interactive = new AgentTemplate(
                "agent-1",
                "Support Agent",
                "",
                List.of(),
                false,
                List.of(),
                AgentType.INTERACTIVE);
        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(interactive).stream());
        when(assignmentService.isAssignedToUser(eq(interactive), eq("alice"))).thenReturn(true);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.delegateTask(schedulerProvider, agentRepo, jobRepo, sessionRepo, assignmentService, authorizationService);

        String output = tool.call("{\"agentName\":\"Support Agent\",\"jobUuid\":\"job-template-1\",\"prompt\":\"Classify this content\"}");
        Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});

        assertEquals("error", result.get("status"));
        assertEquals("INTERACTIVE", result.get("agentType"));
    }

        @Test
        void delegateTask_suspendsWhenTargetAgentNotAssignedToRequester() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AiSchedulerService> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(schedulerService);

        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        AgentAssignmentService assignmentService = mock(AgentAssignmentService.class);
        InToolAuthorizationService authorizationService = mock(InToolAuthorizationService.class);

        AgentTemplate triage = new AgentTemplate(
            "agent-1",
            "Triage Agent",
            "",
            List.of(),
            false,
            List.of(),
            AgentType.BACKGROUND,
            List.of(),
            List.of(),
            List.of(),
            null);
        when(agentRepo.list(0, Integer.MAX_VALUE)).thenReturn(List.of(triage).stream());
        when(assignmentService.isAssignedToUser(eq(triage), eq("alice"))).thenReturn(false);
        doThrow(new ToolSuspensionException("delegateTask", "{}", "approval required", null, null))
            .when(authorizationService)
            .requireAdminApproval(anyString(), anyString(), anyString(), anyString(), anyString());

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", "n/a"));
        org.slf4j.MDC.put("sessionUuid", "session-parent-1");

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.delegateTask(schedulerProvider, agentRepo, jobRepo, sessionRepo, assignmentService, authorizationService);

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
            () -> tool.call("{\"agentName\":\"Triage Agent\",\"prompt\":\"Classify this content\"}"));
        assertTrue(ex.getCause() instanceof ToolSuspensionException);
        verify(schedulerService, never()).scheduleDelegatedDynamicJob(any(ScheduledJob.class), anyString(), anyList());
        }
}
