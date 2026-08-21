package sh.vork.surface.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.service.ChatService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.security.VorkUser;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillParameter;
import sh.vork.skill.SkillVisibility;
import sh.vork.surface.Surface;

class SurfaceServiceTest {

    @Test
    void update_rejectsPublishedNavRouteWithoutIcon() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<Surface> surfaceRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        ChatService chatService = mock(ChatService.class);

        Surface existing = new Surface("surface-1", "surfaceone", "Surface One", "", "session-1", "", List.of(), List.of(), List.of(), 1L, 1L);
        when(surfaceRepo.get("surface-1")).thenReturn(existing);
        when(userRepo.get("alice")).thenReturn(new VorkUser("alice", "Alice", "hash", "USER", true, 1L, 1L));

        SurfaceService service = new SurfaceService(surfaceRepo, skillRepo, userRepo, chatService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.update(
                        "surface-1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        "",
                        List.of("alice"),
                        new Surface.AccessPolicy(false, true, "", false, "", false, "")));

        assertTrue(ex.getMessage().contains("Nav Button icon is required"));
    }

    @Test
    void update_rejectsDuplicatePrivatePathAcrossSurfaces() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<Surface> surfaceRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        ChatService chatService = mock(ChatService.class);

        Surface existing = new Surface("surface-1", "surfaceone", "Surface One", "", "session-1", "", List.of(), List.of(), List.of(), 1L, 1L);
        Surface conflict = new Surface(
                "surface-2",
                "surfacetwo",
                "Surface Two",
                "",
                "session-2",
                "",
                List.of(),
                List.of(),
                List.of(),
                true,
                "",
                List.of("alice"),
                new Surface.AccessPolicy(false, false, "", true, "portal-entry", false, ""),
                "vork",
                "surface2",
                "SNAPSHOT",
                sh.vork.surface.ArtifactStatus.SNAPSHOT,
                1L,
                1L);

        when(surfaceRepo.get("surface-1")).thenReturn(existing);
        when(userRepo.get("alice")).thenReturn(new VorkUser("alice", "Alice", "hash", "USER", true, 1L, 1L));
        when(surfaceRepo.list(0, Integer.MAX_VALUE)).thenReturn(java.util.stream.Stream.of(existing, conflict));

        SurfaceService service = new SurfaceService(surfaceRepo, skillRepo, userRepo, chatService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.update(
                        "surface-1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        "",
                        List.of("alice"),
                        new Surface.AccessPolicy(false, false, "", true, "portal-entry", false, "")));

        assertEquals("Access path already exists: portal-entry", ex.getMessage());
    }

    @Test
    void update_rejectsUnknownSkillUuid() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<Surface> surfaceRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        ChatService chatService = mock(ChatService.class);

        Surface existing = new Surface("surface-1", "surfaceone", "Surface One", "", "session-1", "", List.of(), List.of(), List.of(), 1L, 1L);
        when(surfaceRepo.get("surface-1")).thenReturn(existing);
        when(skillRepo.get("missing-skill")).thenReturn(null);

        SurfaceService service = new SurfaceService(surfaceRepo, skillRepo, userRepo, chatService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.update("surface-1", null, null, List.of("missing-skill"), null, null, null, null, null, null));

        assertEquals("Unknown skill UUID in skillUuids: missing-skill", ex.getMessage());
    }

    @Test
    void update_rejectsSkillWithoutJsonOutputContract() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<Surface> surfaceRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        ChatService chatService = mock(ChatService.class);

        Surface existing = new Surface("surface-1", "surfaceone", "Surface One", "", "session-1", "", List.of(), List.of(), List.of(), 1L, 1L);
        when(surfaceRepo.get("surface-1")).thenReturn(existing);

        Skill notEligible = new Skill(
                "skill-1",
                "Not Eligible",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                List.<SkillParameter>of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                "none",
                "",
                1L,
                1L,
                1L,
                List.of(),
                List.of());

        when(skillRepo.get("skill-1")).thenReturn(notEligible);

        SurfaceService service = new SurfaceService(surfaceRepo, skillRepo, userRepo, chatService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.update("surface-1", null, null, List.of("skill-1"), null, null, null, null, null, null));

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("must declare outputContentType=application/json"));
    }

    @Test
    void update_acceptsEligibleSkillAssignments() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<Surface> surfaceRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        ChatService chatService = mock(ChatService.class);

        Surface existing = new Surface("surface-1", "surfaceone", "Surface One", "", "session-1", "", List.of(), List.of(), List.of(), 1L, 1L);
        when(surfaceRepo.get("surface-1")).thenReturn(existing);
        when(chatService.setSessionReflectionBindings("session-1", List.of())).thenReturn(mock(AiSession.class));

        Skill eligible = new Skill(
                "skill-1",
                "Eligible",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                List.<SkillParameter>of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                "application/json",
                "{\"type\":\"object\"}",
                1L,
                1L,
                1L,
                List.of(),
                List.of());

        when(skillRepo.get("skill-1")).thenReturn(eligible);

        SurfaceService service = new SurfaceService(surfaceRepo, skillRepo, userRepo, chatService);
        Surface updated = service.update("surface-1", "Surface One", null, List.of("skill-1", "skill-1"), List.of(), List.of(), null, null, null, null);

        assertNotNull(updated);
        assertEquals(List.of("skill-1"), updated.skillUuids());

        ArgumentCaptor<Surface> captor = ArgumentCaptor.forClass(Surface.class);
        verify(surfaceRepo).save(captor.capture());
        assertEquals(List.of("skill-1"), captor.getValue().skillUuids());
    }

    @Test
    void ensureExecutionSession_createsDedicatedExecutionSessionAndPersistsIt() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<Surface> surfaceRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
        ChatService chatService = mock(ChatService.class);

        Surface existing = new Surface("surface-1", "surfaceone", "Surface One", "", "editor-session-1", "", List.of(), List.of(), List.of(), 1L, 1L);
        when(surfaceRepo.get("surface-1")).thenReturn(existing);

        AiSession execSession = new AiSession(
                "exec-session-1",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "admin",
                "Execution Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of());

        when(chatService.createNewSession(AiProvider.GEMINI)).thenReturn(execSession);
        when(chatService.setSessionReflectionBindings("exec-session-1", List.of())).thenReturn(execSession);

        SurfaceService service = new SurfaceService(surfaceRepo, skillRepo, userRepo, chatService);
        AiSession actual = service.ensureExecutionSession("surface-1", "admin");

        assertEquals("exec-session-1", actual.uuid());

        ArgumentCaptor<Surface> captor = ArgumentCaptor.forClass(Surface.class);
        verify(surfaceRepo).save(captor.capture());
        assertEquals("editor-session-1", captor.getValue().sessionUuid());
        assertEquals("exec-session-1", captor.getValue().executionSessionUuid());
    }

        @Test
        void updatePublicationSettings_allowsUpdatesForImmutableSurface() {
                @SuppressWarnings("unchecked")
                DatabaseRepository<Surface> surfaceRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepo = mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<VorkUser> userRepo = mock(DatabaseRepository.class);
                ChatService chatService = mock(ChatService.class);

                Surface existing = new Surface(
                                "surface-1",
                                "surfaceone",
                                "Surface One",
                                "",
                                "session-1",
                                "",
                                List.of(),
                                List.of(),
                                List.of(),
                                false,
                                "",
                                List.of(),
                                Surface.AccessPolicy.defaultPolicy(),
                                "vork",
                                "surface1",
                                "1.0",
                                sh.vork.surface.ArtifactStatus.SUBMITTED,
                                1L,
                                1L);

                when(surfaceRepo.get("surface-1")).thenReturn(existing);
                when(userRepo.get("alice")).thenReturn(new VorkUser("alice", "Alice", "hash", "USER", true, 1L, 1L));

                SurfaceService service = new SurfaceService(surfaceRepo, skillRepo, userRepo, chatService);
                Surface updated = service.updatePublicationSettings(
                                "surface-1",
                                true,
                                List.of("alice"),
                                new Surface.AccessPolicy(true, false, "", false, "", false, ""));

                assertNotNull(updated);
                assertTrue(updated.published());
                assertEquals(List.of("alice"), updated.assignedUserUuids());
                assertTrue(updated.accessPolicy().homeScreenEnabled());

                ArgumentCaptor<Surface> captor = ArgumentCaptor.forClass(Surface.class);
                verify(surfaceRepo).save(captor.capture());
                assertEquals(sh.vork.surface.ArtifactStatus.SUBMITTED, captor.getValue().artifactStatus());
        }
}
