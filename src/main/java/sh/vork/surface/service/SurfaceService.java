package sh.vork.surface.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.lifecycle.AgentTemplateSeeder;
import sh.vork.ai.service.ChatService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.surface.Surface;
import sh.vork.util.ToolIdGenerator;

import java.util.List;
import java.util.UUID;

/**
 * Business logic for {@link Surface} CRUD and session lifecycle.
 */
@Service
public class SurfaceService {

    private static final Logger log = LoggerFactory.getLogger(SurfaceService.class);

    private final DatabaseRepository<Surface> surfaceRepository;
    private final ChatService chatService;

    public SurfaceService(DatabaseRepository<Surface> surfaceRepository,
                          ChatService chatService) {
        this.surfaceRepository = surfaceRepository;
        this.chatService = chatService;
    }

    /**
     * Returns all surfaces in creation-order (oldest first).
     */
    public List<Surface> list() {
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            return stream.toList();
        }
    }

    public Surface get(String uuid) {
        return surfaceRepository.get(uuid);
    }

    public Surface getByToolId(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        String normalized = ToolIdGenerator.normalizeBase(toolId, "surface");
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(surface -> normalized.equals(ToolIdGenerator.normalizeBase(surface.toolId(), "surface")))
                    .findFirst()
                    .orElse(null);
        }
    }

    public Surface resolveByUuidOrToolId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        Surface direct = surfaceRepository.get(identifier);
        return direct != null ? direct : getByToolId(identifier);
    }

    /**
     * Creates a new surface, an associated AI session, and activates the
     * Surface Developer agent.
     */
    public Surface create(String name, String description, String username) {
        String uuid = UUID.randomUUID().toString();
        String toolId = uniqueSurfaceToolId(name, null);
        long now = System.currentTimeMillis();

        AiSession session = chatService.createNewSession(AiProvider.GEMINI);
        String switched = chatService.switchActiveAgentById(session.uuid(),
                AgentTemplateSeeder.UUID_SURFACE_DEVELOPER);
        if (switched == null) {
            log.warn("Surface Developer agent not found; surface session will use default agent");
        }

        Surface surface = new Surface(
                uuid,
            toolId,
                name == null || name.isBlank() ? "Untitled Surface" : name,
                description == null ? "" : description,
                session.uuid(),
                List.of(),
                List.of(),
                List.of(),
                now,
                now);
        surfaceRepository.save(surface);

        log.info("Created surface [uuid={}, name={}, sessionUuid={}, user={}]",
                uuid, surface.name(), session.uuid(), username);
        return surface;
    }

    /**
     * Updates a surface's metadata and assignment references.
     *
     * @return the updated surface, or {@code null} if no surface with the given UUID exists
     */
    public Surface update(String uuid,
                          String name,
                          String description,
                          List<String> skillUuids,
                          List<String> reflectionBindingUuids,
                          List<String> jobUuids) {
        Surface existing = surfaceRepository.get(uuid);
        if (existing == null) {
            return null;
        }

        Surface updated = new Surface(
                existing.uuid(),
            uniqueSurfaceToolId(name == null || name.isBlank() ? existing.name() : name, existing.uuid()),
                name == null || name.isBlank() ? existing.name() : name,
                description == null ? existing.description() : description,
                existing.sessionUuid(),
                skillUuids == null ? existing.skillUuids() : skillUuids,
                reflectionBindingUuids == null ? existing.reflectionBindingUuids() : reflectionBindingUuids,
                jobUuids == null ? existing.jobUuids() : jobUuids,
                existing.createdAt(),
                System.currentTimeMillis());
        surfaceRepository.save(updated);

        syncSessionReflectionBindings(updated.sessionUuid(), updated.reflectionBindingUuids());

        log.info("Updated surface [uuid={}, name={}]", uuid, updated.name());
        return updated;
    }

    /**
     * Deletes the surface record.  The backing AI session is left intact.
     *
     * @return {@code true} if the surface existed and was deleted
     */
    public boolean delete(String uuid) {
        Surface existing = surfaceRepository.get(uuid);
        if (existing == null) {
            return false;
        }
        surfaceRepository.delete(uuid);
        log.info("Deleted surface [uuid={}, name={}]", uuid, existing.name());
        return true;
    }

    /**
     * Returns the AI session linked to a surface, creating (and persisting) a
     * new one if necessary.  The Surface Developer agent is activated on any
     * newly created session.
     *
     * @throws IllegalArgumentException if the surface does not exist
     */
    public AiSession ensureSession(String surfaceUuid, String username) {
        Surface surface = resolveByUuidOrToolId(surfaceUuid);
        if (surface == null) {
            throw new IllegalArgumentException("Surface not found: " + surfaceUuid);
        }

        AiSession session = null;
        if (surface.sessionUuid() != null && !surface.sessionUuid().isBlank()) {
            try {
                session = chatService.getSessionForCurrentUser(surface.sessionUuid());
            } catch (IllegalStateException ex) {
                log.debug("Existing surface session not accessible; creating new session [surface={}, reason={}]",
                        surfaceUuid, ex.getMessage());
            }
        }

        if (session == null) {
            session = chatService.createNewSession(AiProvider.GEMINI);
            chatService.switchActiveAgentById(session.uuid(), AgentTemplateSeeder.UUID_SURFACE_DEVELOPER);
            Surface updated = new Surface(
                    surface.uuid(),
                    surface.toolId(),
                    surface.name(),
                    surface.description(),
                    session.uuid(),
                    surface.skillUuids(),
                    surface.reflectionBindingUuids(),
                    surface.jobUuids(),
                    surface.createdAt(),
                    System.currentTimeMillis());
            surfaceRepository.save(updated);
            log.info("Linked new session to surface [surfaceUuid={}, sessionUuid={}, user={}]",
                    surfaceUuid, session.uuid(), username);
        }

        syncSessionReflectionBindings(session.uuid(), surface.reflectionBindingUuids());

        return session;
    }

    private void syncSessionReflectionBindings(String sessionUuid, List<String> reflectionBindingUuids) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return;
        }
        try {
            chatService.setSessionReflectionBindings(sessionUuid, reflectionBindingUuids);
        } catch (Exception ex) {
            log.warn("Failed to sync surface reflection bindings to session [sessionUuid={}, reason={}]",
                    sessionUuid, ex.getMessage());
        }
    }

    private String uniqueSurfaceToolId(String preferredSource, String excludeSurfaceUuid) {
        return ToolIdGenerator.unique(
                preferredSource,
                "surface",
                candidate -> isSurfaceToolIdAvailable(candidate, excludeSurfaceUuid));
    }

    private boolean isSurfaceToolIdAvailable(String candidate, String excludeSurfaceUuid) {
        String normalizedCandidate = ToolIdGenerator.normalizeBase(candidate, "surface");
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            return stream.noneMatch(surface -> {
                if (excludeSurfaceUuid != null && excludeSurfaceUuid.equals(surface.uuid())) {
                    return false;
                }
                String existing = ToolIdGenerator.normalizeBase(surface.toolId(), "surface");
                return normalizedCandidate.equals(existing);
            });
        }
    }
}
