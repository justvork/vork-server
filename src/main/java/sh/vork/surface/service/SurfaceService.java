package sh.vork.surface.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.lifecycle.AgentTemplateSeeder;
import sh.vork.ai.service.ChatService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.skill.Skill;
import sh.vork.surface.Surface;
import sh.vork.util.ToolIdGenerator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Business logic for {@link Surface} CRUD and session lifecycle.
 */
@Service
public class SurfaceService {

    private static final Logger log = LoggerFactory.getLogger(SurfaceService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SNAPSHOT_VERSION = "SNAPSHOT";

    private final DatabaseRepository<Surface> surfaceRepository;
    private final DatabaseRepository<Skill> skillRepository;
    private final ChatService chatService;

    public SurfaceService(DatabaseRepository<Surface> surfaceRepository,
                          DatabaseRepository<Skill> skillRepository,
                          ChatService chatService) {
        this.surfaceRepository = surfaceRepository;
        this.skillRepository = skillRepository;
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

    /**
     * Creates a new surface, an associated AI session, and activates the
     * Surface Developer agent.
     */
    public Surface create(String name, String description, String username) {
        String fallbackArtifactId = ToolIdGenerator.normalizeBase(name, "surface");
        return create(name, description, username, "vork", fallbackArtifactId);
    }

    public Surface create(String name, String description, String username, String groupId, String artifactId) {
        String uuid = toVid(groupId, artifactId, SNAPSHOT_VERSION);
        if (surfaceRepository.get(uuid) != null) {
            throw new IllegalArgumentException("Surface artifact already exists: " + uuid);
        }
        String toolId = uniqueSurfaceToolId(artifactId, null);
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
                "",
                List.of(),
                List.of(),
                List.of(),
            groupId,
            artifactId,
            SNAPSHOT_VERSION,
            sh.vork.surface.ArtifactStatus.SNAPSHOT,
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
        if (!existing.isSnapshotMutable()) {
            throw new IllegalArgumentException("Only SNAPSHOT surfaces can be edited.");
        }

        List<String> validatedSkillUuids = skillUuids == null
                ? existing.skillUuids()
                : validateAndNormalizeSurfaceSkillUuids(skillUuids);

        Surface updated = new Surface(
                existing.uuid(),
                existing.toolId(),
                name == null || name.isBlank() ? existing.name() : name,
                description == null ? existing.description() : description,
                existing.sessionUuid(),
                existing.executionSessionUuid(),
                validatedSkillUuids,
                reflectionBindingUuids == null ? existing.reflectionBindingUuids() : reflectionBindingUuids,
                jobUuids == null ? existing.jobUuids() : jobUuids,
            existing.groupId(),
            existing.artifactId(),
            existing.version(),
            existing.artifactStatus(),
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
        if (!existing.isDeletable()) {
            throw new IllegalArgumentException("Only SNAPSHOT, SUBMITTED, or REJECTED surfaces can be deleted.");
        }
        surfaceRepository.delete(existing.uuid());
        log.info("Deleted surface [uuid={}, name={}]", existing.uuid(), existing.name());
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
        Surface surface = surfaceRepository.get(surfaceUuid);
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
                    surface.executionSessionUuid(),
                    surface.skillUuids(),
                    surface.reflectionBindingUuids(),
                    surface.jobUuids(),
                    surface.groupId(),
                    surface.artifactId(),
                    surface.version(),
                    surface.artifactStatus(),
                    surface.createdAt(),
                    System.currentTimeMillis());
            surfaceRepository.save(updated);
            log.info("Linked new session to surface [surfaceUuid={}, sessionUuid={}, user={}]",
                    surfaceUuid, session.uuid(), username);
        }

        syncSessionReflectionBindings(session.uuid(), surface.reflectionBindingUuids());

        return session;
    }

    /**
     * Returns the dedicated execution session linked to a surface, creating and persisting
     * one if necessary.
     */
    public AiSession ensureExecutionSession(String surfaceUuid, String username) {
        Surface surface = surfaceRepository.get(surfaceUuid);
        if (surface == null) {
            throw new IllegalArgumentException("Surface not found: " + surfaceUuid);
        }

        AiSession session = null;
        if (surface.executionSessionUuid() != null && !surface.executionSessionUuid().isBlank()) {
            try {
                session = chatService.getSessionForCurrentUser(surface.executionSessionUuid());
            } catch (IllegalStateException ex) {
                log.debug("Existing execution session not accessible; creating new session [surface={}, reason={}]",
                        surfaceUuid, ex.getMessage());
            }
        }

        if (session == null) {
            session = chatService.createNewSession(AiProvider.GEMINI);
            Surface updated = new Surface(
                    surface.uuid(),
                    surface.toolId(),
                    surface.name(),
                    surface.description(),
                    surface.sessionUuid(),
                    session.uuid(),
                    surface.skillUuids(),
                    surface.reflectionBindingUuids(),
                    surface.jobUuids(),
                    surface.groupId(),
                    surface.artifactId(),
                    surface.version(),
                    surface.artifactStatus(),
                    surface.createdAt(),
                    System.currentTimeMillis());
            surfaceRepository.save(updated);
            log.info("Linked new execution session to surface [surfaceUuid={}, sessionUuid={}, user={}]",
                    surfaceUuid, session.uuid(), username);
        }

        syncSessionReflectionBindings(session.uuid(), surface.reflectionBindingUuids());

        return session;
    }

    public Skill resolveAttachedSkillByPublicIds(String surfaceUuid, String groupId, String skillId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId is required");
        }

        Surface surface = surfaceRepository.get(surfaceUuid);
        if (surface == null) {
            throw new IllegalArgumentException("Surface not found: " + surfaceUuid);
        }

        for (String skillUuid : surface.skillUuids()) {
            Skill skill = skillRepository.get(skillUuid);
            if (skill == null) {
                continue;
            }
            PublicSkillId publicIds = publicIdsFor(skill);
            if (publicIds.groupId().equalsIgnoreCase(groupId.trim())
                    && publicIds.skillId().equalsIgnoreCase(skillId.trim())) {
                return skill;
            }
        }
        throw new IllegalArgumentException("No attached skill matches groupId='" + groupId + "' and skillId='" + skillId + "'.");
    }

    public List<Skill> listAttachedSkills(String surfaceUuid) {
        Surface surface = surfaceRepository.get(surfaceUuid);
        if (surface == null) {
            throw new IllegalArgumentException("Surface not found: " + surfaceUuid);
        }
        List<Skill> skills = new ArrayList<>();
        for (String skillUuid : surface.skillUuids()) {
            Skill skill = skillRepository.get(skillUuid);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return List.copyOf(skills);
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

    public PublicSkillId publicIdsFor(Skill skill) {
        String toolName = skill == null ? "" : skill.toolName();
        int sep = toolName.indexOf('_');
        if (sep <= 0 || sep >= toolName.length() - 1) {
            String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
            return new PublicSkillId(normalized, normalized);
        }
        String groupId = toolName.substring(0, sep).trim().toLowerCase(Locale.ROOT);
        String skillId = toolName.substring(sep + 1).trim().toLowerCase(Locale.ROOT);
        return new PublicSkillId(groupId, skillId);
    }

    private List<String> validateAndNormalizeSurfaceSkillUuids(List<String> skillUuids) {
        if (skillUuids == null || skillUuids.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String skillUuid : skillUuids) {
            if (skillUuid == null || skillUuid.isBlank()) {
                continue;
            }
            String trimmed = skillUuid.trim();
            Skill skill = skillRepository.get(trimmed);
            if (skill == null) {
                throw new IllegalArgumentException("Unknown skill UUID in skillUuids: " + trimmed);
            }
            if (!isSkillSurfaceEligible(skill)) {
                throw new IllegalArgumentException(
                        "Skill '" + skill.name() + "' (" + skill.uuid() + ") must declare outputContentType=application/json and a valid outputSchema before it can be attached to a surface.");
            }
            normalized.add(trimmed);
        }
        return new ArrayList<>(normalized);
    }

    private boolean isSkillSurfaceEligible(Skill skill) {
        String contentType = skill.outputContentType() == null ? "none" : skill.outputContentType().trim().toLowerCase();
        if (!"application/json".equals(contentType)) {
            return false;
        }
        String schema = skill.outputSchema() == null ? "" : skill.outputSchema().trim();
        if (schema.isBlank()) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(schema);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public record PublicSkillId(String groupId, String skillId) {}

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }
}
