package sh.vork.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.mock.MapDatabaseRepository;

class RepositorySessionEnvironmentServiceTest {

    @Test
    void setEnv_updatesSessionEnvironmentVariables() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        String sessionId = "session-env-update";
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                null,
                null,
                null));

        SessionEnvironmentService service = new RepositorySessionEnvironmentService(sessionRepo);

        service.setEnv(sessionId, "activeTargetAnchor", "local");
        service.setEnv(sessionId, "ssh-username-10.0.22.22", "ubuntu");
        service.setEnv(sessionId, "nullable", null);

        Map<String, String> env = service.getEnv(sessionId);
        assertEquals("local", env.get("activeTargetAnchor"));
        assertEquals("ubuntu", env.get("ssh-username-10.0.22.22"));
        assertEquals("", env.get("nullable"));
    }

    @Test
    void setEnv_whenSessionOrKeyInvalid_noChangesApplied() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        String sessionId = "session-env-invalid";
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                null,
                null,
                null));

        SessionEnvironmentService service = new RepositorySessionEnvironmentService(sessionRepo);

        service.setEnv(null, "k", "v");
        service.setEnv("", "k", "v");
        service.setEnv("   ", "k", "v");
        service.setEnv(sessionId, null, "v");
        service.setEnv(sessionId, "", "v");
        service.setEnv(sessionId, "   ", "v");

        assertEquals(Map.of(), service.getEnv(sessionId));
    }

    @Test
    void getEnv_returnsEmptyMapForUnknownOrInvalidSession() {
        SessionEnvironmentService service =
                new RepositorySessionEnvironmentService(new MapDatabaseRepository<>(AiSession.class));

        assertEquals(Map.of(), service.getEnv(null));
        assertEquals(Map.of(), service.getEnv(""));
        assertEquals(Map.of(), service.getEnv("   "));
        assertEquals(Map.of(), service.getEnv("missing"));
    }

    @Test
    void getEnv_returnsImmutableCopy() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        String sessionId = "session-env-immutable";
        sessionRepo.save(new AiSession(
                sessionId,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "anonymous",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                null,
                null,
                null));

        SessionEnvironmentService service = new RepositorySessionEnvironmentService(sessionRepo);
        service.setEnv(sessionId, "k", "v");

        Map<String, String> env = service.getEnv(sessionId);
        assertEquals("v", env.get("k"));
        assertThrows(UnsupportedOperationException.class, () -> env.put("x", "y"));
    }
}
