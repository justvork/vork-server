package sh.vork.scheduling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;
import sh.vork.security.UserManagementService;
import sh.vork.scheduling.domain.ArtifactStatus;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.skill.Skill;

class JobsControllerTest {

    private static UserManagementService userManagementService() {
        UserManagementService userManagementService = mock(UserManagementService.class);
        when(userManagementService.listUsers()).thenReturn(List.of(
                new UserManagementService.UserSummary("alice", "ADMIN", true, 0L, 0L),
                new UserManagementService.UserSummary("bob", "USER", true, 0L, 0L)
        ));
        return userManagementService;
    }

    @Test
    void createJob_usesDeterministicVid() {
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = mock(DatabaseRepository.class);

        when(jobRepository.get("vork-nightlyreport-SNAPSHOT")).thenReturn(null);
        when(schedulerService.scheduleJob(any(ScheduledJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobsController controller = new JobsController(schedulerService, jobRepository, sessionRepository, skillRepository, userManagementService());
        UserDetails user = user("alice");

        JobsController.JobRequest req = new JobsController.JobRequest(
                "Nightly Report",
                "Summarize all errors",
                InvocationType.ONE_TIME,
                Instant.now().toString(),
                0L,
                DurationType.MINUTES,
                null,
                null,
                null,
                240,
                null,
                List.of(),
                List.of(),
                List.of("alice", "bob"),
                "vork",
                "nightlyreport");

        ResponseEntity<?> response = controller.createJob(req, user);

        assertEquals(200, response.getStatusCode().value());
        ScheduledJob created = assertInstanceOf(ScheduledJob.class, response.getBody());
        assertEquals("vork-nightlyreport-SNAPSHOT", created.id());
        assertEquals("vork", created.groupId());
        assertEquals("nightlyreport", created.artifactId());
        assertEquals("SNAPSHOT", created.version());
        assertEquals(ArtifactStatus.SNAPSHOT, created.artifactStatus());
    }

    @Test
    void updateJob_rejectsNonSnapshotJob() {
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = mock(DatabaseRepository.class);

        ScheduledJob existing = job("vork-nightlyreport-SNAPSHOT", "alice", "vork", "nightlyreport", "SNAPSHOT", ArtifactStatus.PUBLISHED);
        when(jobRepository.get(existing.id())).thenReturn(existing);

        JobsController controller = new JobsController(schedulerService, jobRepository, sessionRepository, skillRepository, userManagementService());

        JobsController.JobRequest req = new JobsController.JobRequest(
                "Nightly Report",
                "Updated prompt",
                InvocationType.ONE_TIME,
                Instant.now().toString(),
                0L,
                DurationType.MINUTES,
                null,
                null,
                null,
                240,
                null,
                List.of(),
                List.of(),
                List.of("alice"),
                "vork",
                "nightlyreport");

        ResponseEntity<?> response = controller.updateJob(existing.id(), req, user("alice"));

        assertEquals(403, response.getStatusCode().value());
        verify(schedulerService, never()).scheduleJob(any());
    }

    @Test
    void deleteJob_rejectsNonSnapshotJob() {
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = mock(DatabaseRepository.class);

        ScheduledJob existing = job("vork-nightlyreport-SNAPSHOT", "alice", "vork", "nightlyreport", "SNAPSHOT", ArtifactStatus.PUBLISHED);
        when(jobRepository.get(existing.id())).thenReturn(existing);

        JobsController controller = new JobsController(schedulerService, jobRepository, sessionRepository, skillRepository, userManagementService());

        ResponseEntity<?> response = controller.deleteJob(existing.id(), user("alice"));

        assertEquals(403, response.getStatusCode().value());
        verify(schedulerService, never()).deleteJob(existing.id());
    }

    @Test
    void deleteJob_allowsSubmittedJob() {
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = mock(DatabaseRepository.class);

        ScheduledJob existing = job("vork-nightlyreport-1.0", "alice", "vork", "nightlyreport", "1.0", ArtifactStatus.SUBMITTED);
        when(jobRepository.get(existing.id())).thenReturn(existing);

        JobsController controller = new JobsController(schedulerService, jobRepository, sessionRepository, skillRepository, userManagementService());

        ResponseEntity<?> response = controller.deleteJob(existing.id(), user("alice"));

        assertEquals(200, response.getStatusCode().value());
        verify(schedulerService).deleteJob(existing.id());
    }

    @Test
    void importJob_rejectsNonSnapshotArtifactStatus() {
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = mock(DatabaseRepository.class);

        JobsController controller = new JobsController(schedulerService, jobRepository, sessionRepository, skillRepository, userManagementService());

        ScheduledJob incoming = job("vork-nightlyreport-SNAPSHOT", "someone", "vork", "nightlyreport", "SNAPSHOT", ArtifactStatus.PUBLISHED);
        JobsController.JobExportJob exportJob = new JobsController.JobExportJob(
            incoming.id(),
            incoming.name(),
            incoming.aiPrompt(),
            incoming.invocationType(),
            incoming.repeatDuration(),
            incoming.durationType(),
            incoming.agentTemplateId(),
            incoming.provider(),
            incoming.modelId(),
            incoming.oobTimeoutMinutes(),
            incoming.expectedOutput(),
            incoming.skillUuids(),
            incoming.toolIds(),
            incoming.groupId(),
            incoming.artifactId(),
            incoming.version(),
            incoming.artifactStatus());
        JobsController.JobExportPackage pkg = new JobsController.JobExportPackage("1.0", exportJob, null);

        ResponseEntity<?> response = controller.importJob(pkg, user("alice"));

        assertEquals(400, response.getStatusCode().value());
        JobsController.JobImportResult result = assertInstanceOf(JobsController.JobImportResult.class, response.getBody());
        assertTrue(result.message().contains("Only SNAPSHOT"));
    }

    @Test
    void exportJob_supportsDeterministicVid() {
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = mock(DatabaseRepository.class);

        ScheduledJob existing = job("vork-nightlyreport-SNAPSHOT", "alice", "vork", "nightlyreport", "SNAPSHOT", ArtifactStatus.SNAPSHOT);
        when(jobRepository.get(existing.id())).thenReturn(existing);

        JobsController controller = new JobsController(schedulerService, jobRepository, sessionRepository, skillRepository, userManagementService());

        ResponseEntity<?> response = controller.exportJob(existing.id(), user("alice"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/json", response.getHeaders().getContentType().toString());
        Object body = response.getBody();
        assertTrue(body instanceof String);
        assertTrue(((String) body).contains("\"vorkJobExport\""));
        assertTrue(!((String) body).contains("\"userId\""));
        assertTrue(!((String) body).contains("\"status\""));
        assertTrue(!((String) body).contains("\"startTime\""));
        assertTrue(!((String) body).contains("\"lastExecutionTime\""));
        assertTrue(!((String) body).contains("\"nextExecutionTime\""));
    }

    @Test
    void createJob_rejectsDuplicateVid() {
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> jobRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = mock(DatabaseRepository.class);

        ScheduledJob existing = job("vork-nightlyreport-SNAPSHOT", "alice", "vork", "nightlyreport", "SNAPSHOT", ArtifactStatus.SNAPSHOT);
        when(jobRepository.get(existing.id())).thenReturn(existing);

        JobsController controller = new JobsController(schedulerService, jobRepository, sessionRepository, skillRepository, userManagementService());

        JobsController.JobRequest req = new JobsController.JobRequest(
                "Nightly Report",
                "Summarize all errors",
                InvocationType.ONE_TIME,
                Instant.now().toString(),
                0L,
                DurationType.MINUTES,
                null,
                null,
                null,
                240,
                null,
                List.of(),
                List.of(),
                List.of("alice"),
                "vork",
                "nightlyreport");

        ResponseEntity<?> response = controller.createJob(req, user("alice"));

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertTrue(String.valueOf(body.get("error")).contains("already exists"));
        verify(schedulerService, never()).scheduleJob(any());
    }

    private static ScheduledJob job(String id,
                                    String userId,
                                    String groupId,
                                    String artifactId,
                                    String version,
                                    ArtifactStatus artifactStatus) {
        return new ScheduledJob(
                id,
                "Nightly Report",
                "Summarize all errors",
                null,
                userId,
                InvocationType.ONE_TIME,
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
                List.of(userId),
                groupId,
                artifactId,
                version,
                artifactStatus);
    }

    private static UserDetails user(String username) {
        UserDetails user = mock(UserDetails.class);
        when(user.getUsername()).thenReturn(username);
        return user;
    }
}
