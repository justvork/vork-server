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
import sh.vork.security.VorkUser;
import sh.vork.skill.Skill;
import sh.vork.surface.Surface;
import sh.vork.util.ToolIdGenerator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Business logic for {@link Surface} CRUD and session lifecycle.
 */
@Service
public class SurfaceService {

    private static final Logger log = LoggerFactory.getLogger(SurfaceService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SNAPSHOT_VERSION = "SNAPSHOT";
    private static final Pattern ROUTE_PATH_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");
    private static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:image/(png|jpeg|jpg|gif|webp|svg\\+xml);base64,[A-Za-z0-9+/=\\r\\n]+$");
    private static final int MAX_LOGO_DATA_URL_LENGTH = 1_500_000;

    private final DatabaseRepository<Surface> surfaceRepository;
    private final DatabaseRepository<Skill> skillRepository;
    private final DatabaseRepository<VorkUser> userRepository;
    private final ChatService chatService;

    public SurfaceService(DatabaseRepository<Surface> surfaceRepository,
                          DatabaseRepository<Skill> skillRepository,
                          DatabaseRepository<VorkUser> userRepository,
                          ChatService chatService) {
        this.surfaceRepository = surfaceRepository;
        this.skillRepository = skillRepository;
        this.userRepository = userRepository;
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
                false,
                "",
                List.of(),
                Surface.AccessPolicy.defaultPolicy(),
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
                          List<String> jobUuids,
                          Boolean published,
                          String logoDataUrl,
                          List<String> assignedUserUuids,
                          Surface.AccessPolicy accessPolicy) {
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

        boolean nextPublished = published == null ? existing.published() : published;
        String nextLogoDataUrl = logoDataUrl == null ? existing.logoDataUrl() : normalizeLogoDataUrl(logoDataUrl);
        List<String> nextAssignedUserUuids = assignedUserUuids == null
            ? existing.assignedUserUuids()
            : validateAndNormalizeAssignedUsers(assignedUserUuids);
        Surface.AccessPolicy nextAccessPolicy = accessPolicy == null
            ? existing.accessPolicy()
            : normalizeAccessPolicy(accessPolicy);

        validatePublishConfiguration(existing.uuid(), nextPublished, nextLogoDataUrl, nextAccessPolicy, nextAssignedUserUuids);

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
                nextPublished,
                nextLogoDataUrl,
                nextAssignedUserUuids,
                nextAccessPolicy,
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
         * Updates publication-facing settings regardless of artifact mutability.
         * This is used for immutable versions where publication access can still be
         * controlled locally (published toggle, assignments, and access routes).
         *
         * @return the updated surface, or {@code null} if no surface with the given UUID exists
         */
        public Surface updatePublicationSettings(String uuid,
                             Boolean published,
                             List<String> assignedUserUuids,
                             Surface.AccessPolicy accessPolicy) {
        Surface existing = surfaceRepository.get(uuid);
        if (existing == null) {
            return null;
        }

        boolean nextPublished = published == null ? existing.published() : published;
        List<String> nextAssignedUserUuids = assignedUserUuids == null
            ? existing.assignedUserUuids()
            : validateAndNormalizeAssignedUsers(assignedUserUuids);
        Surface.AccessPolicy nextAccessPolicy = accessPolicy == null
            ? existing.accessPolicy()
            : normalizeAccessPolicy(accessPolicy);

        validatePublishConfiguration(
            existing.uuid(),
            nextPublished,
            existing.logoDataUrl(),
            nextAccessPolicy,
            nextAssignedUserUuids);

        Surface updated = new Surface(
            existing.uuid(),
            existing.toolId(),
            existing.name(),
            existing.description(),
            existing.sessionUuid(),
            existing.executionSessionUuid(),
            existing.skillUuids(),
            existing.reflectionBindingUuids(),
            existing.jobUuids(),
            nextPublished,
            existing.logoDataUrl(),
            nextAssignedUserUuids,
            nextAccessPolicy,
            existing.groupId(),
            existing.artifactId(),
            existing.version(),
            existing.artifactStatus(),
            existing.createdAt(),
            System.currentTimeMillis());

        surfaceRepository.save(updated);
        log.info("Updated surface publication settings [uuid={}, published={}]", uuid, updated.published());
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
                    surface.published(),
                    surface.logoDataUrl(),
                    surface.assignedUserUuids(),
                    surface.accessPolicy(),
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
                    surface.published(),
                    surface.logoDataUrl(),
                    surface.assignedUserUuids(),
                    surface.accessPolicy(),
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

    public Surface findPublishedByPrivatePath(String privatePath) {
        String normalizedPath = normalizeRoutePath(privatePath);
        if (normalizedPath.isBlank()) {
            return null;
        }
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(surface -> surface != null && surface.published())
                    .filter(surface -> surface.accessPolicy() != null && surface.accessPolicy().privateUrlEnabled())
                    .filter(surface -> normalizedPath.equals(surface.accessPolicy().privateUrlPath()))
                    .findFirst()
                    .orElse(null);
        }
    }

    public Surface findPublishedByPublicPath(String publicPath) {
        String normalizedPath = normalizeRoutePath(publicPath);
        if (normalizedPath.isBlank()) {
            return null;
        }
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(surface -> surface != null && surface.published())
                    .filter(surface -> surface.accessPolicy() != null && surface.accessPolicy().publicUrlEnabled())
                    .filter(surface -> normalizedPath.equals(surface.accessPolicy().publicUrlPath()))
                    .findFirst()
                    .orElse(null);
        }
    }

    public List<SurfaceAppLink> listPublishedHomeAppsForUser(String username) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isBlank()) {
            return List.of();
        }
        List<SurfaceAppLink> links = new ArrayList<>();
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            stream
                    .filter(surface -> surface != null && surface.published())
                    .filter(surface -> surface.accessPolicy() != null && surface.accessPolicy().homeScreenEnabled())
                    .filter(surface -> isUserAssigned(surface, normalizedUsername))
                    .forEach(surface -> links.add(toAppLink(surface)));
        }
        return List.copyOf(links);
    }

    public List<SurfaceAppLink> listPublishedNavAppsForUser(String username) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isBlank()) {
            return List.of();
        }
        List<SurfaceAppLink> links = new ArrayList<>();
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            stream
                    .filter(surface -> surface != null && surface.published())
                    .filter(surface -> surface.accessPolicy() != null && surface.accessPolicy().navButtonEnabled())
                    .filter(surface -> isUserAssigned(surface, normalizedUsername))
                    .forEach(surface -> links.add(toAppLink(surface)));
        }
        return List.copyOf(links);
    }

    public boolean isUserAssigned(Surface surface, String username) {
        if (surface == null || username == null || username.isBlank()) {
            return false;
        }
        if (surface.assignedUserUuids() == null || surface.assignedUserUuids().isEmpty()) {
            return false;
        }
        return surface.assignedUserUuids().stream().anyMatch(username::equals);
    }

    public record SurfaceAppLink(String uuid, String name, String description, String logoDataUrl, String navIcon, String url) {
    }

    private SurfaceAppLink toAppLink(Surface surface) {
        String navIcon = surface.accessPolicy() == null ? "" : surface.accessPolicy().navButtonIcon();
        String url = appEntryUrl(surface);
        return new SurfaceAppLink(
                surface.uuid(),
                surface.name(),
                surface.description(),
                surface.logoDataUrl(),
                navIcon,
                url);
    }

    private String appEntryUrl(Surface surface) {
        return "/apps/published/" + surface.uuid() + "/";
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

    private List<String> validateAndNormalizeAssignedUsers(List<String> assignedUserUuids) {
        if (assignedUserUuids == null || assignedUserUuids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String username : assignedUserUuids) {
            if (username == null || username.isBlank()) {
                continue;
            }
            String trimmed = username.trim();
            VorkUser user = userRepository.get(trimmed);
            if (user == null) {
                throw new IllegalArgumentException("Unknown user in assigned users: " + trimmed);
            }
            if (!user.isEnabled()) {
                throw new IllegalArgumentException("Assigned user is disabled: " + trimmed);
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private Surface.AccessPolicy normalizeAccessPolicy(Surface.AccessPolicy policy) {
        if (policy == null) {
            return Surface.AccessPolicy.defaultPolicy();
        }
        String navIcon = policy.navButtonIcon() == null ? "" : policy.navButtonIcon().trim();
        String privatePath = normalizeRoutePath(policy.privateUrlPath());
        String publicPath = normalizeRoutePath(policy.publicUrlPath());
        if (!policy.privateUrlEnabled()) {
            privatePath = "";
        }
        if (!policy.publicUrlEnabled()) {
            publicPath = "";
        }
        if (!policy.navButtonEnabled()) {
            navIcon = "";
        }
        return new Surface.AccessPolicy(
                policy.homeScreenEnabled(),
                policy.navButtonEnabled(),
                navIcon,
                policy.privateUrlEnabled(),
                privatePath,
                policy.publicUrlEnabled(),
                publicPath);
    }

    private void validatePublishConfiguration(String surfaceUuid,
                                              boolean published,
                                              String logoDataUrl,
                                              Surface.AccessPolicy accessPolicy,
                                              List<String> assignedUsers) {
        if (!published) {
            return;
        }
        Surface.AccessPolicy effectivePolicy = accessPolicy == null ? Surface.AccessPolicy.defaultPolicy() : accessPolicy;
        boolean hasRoute = effectivePolicy.homeScreenEnabled()
                || effectivePolicy.navButtonEnabled()
                || effectivePolicy.privateUrlEnabled()
                || effectivePolicy.publicUrlEnabled();
        if (!hasRoute) {
            throw new IllegalArgumentException("At least one access route must be enabled when a surface is published.");
        }
        if ((effectivePolicy.homeScreenEnabled() || effectivePolicy.navButtonEnabled() || effectivePolicy.privateUrlEnabled())
                && (assignedUsers == null || assignedUsers.isEmpty())) {
            throw new IllegalArgumentException("At least one assigned user is required for home, nav, or private access policies.");
        }
        if (effectivePolicy.navButtonEnabled() && (effectivePolicy.navButtonIcon() == null || effectivePolicy.navButtonIcon().isBlank())) {
            throw new IllegalArgumentException("Nav Button icon is required when Nav Button access is enabled.");
        }
        if (effectivePolicy.privateUrlEnabled()) {
            validateRoutePathOrThrow("Private URL", effectivePolicy.privateUrlPath());
            ensureRoutePathUnique(surfaceUuid, effectivePolicy.privateUrlPath());
        }
        if (effectivePolicy.publicUrlEnabled()) {
            validateRoutePathOrThrow("Public URL", effectivePolicy.publicUrlPath());
            ensureRoutePathUnique(surfaceUuid, effectivePolicy.publicUrlPath());
        }
        if (effectivePolicy.privateUrlEnabled() && effectivePolicy.publicUrlEnabled()
                && effectivePolicy.privateUrlPath().equals(effectivePolicy.publicUrlPath())) {
            throw new IllegalArgumentException("Private URL and Public URL paths must be different.");
        }
        if (!logoDataUrl.isBlank()) {
            validateLogoDataUrlOrThrow(logoDataUrl);
        }
    }

    private void validateRoutePathOrThrow(String label, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(label + " path is required when enabled.");
        }
        if (!ROUTE_PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException(label + " path must be 3-64 characters and use lowercase letters, numbers, or '-'.");
        }
    }

    private void ensureRoutePathUnique(String surfaceUuid, String path) {
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            boolean exists = stream.anyMatch(surface -> {
                if (surface == null) {
                    return false;
                }
                if (surfaceUuid != null && surfaceUuid.equals(surface.uuid())) {
                    return false;
                }
                Surface.AccessPolicy policy = surface.accessPolicy();
                if (policy == null) {
                    return false;
                }
                if (policy.privateUrlEnabled() && path.equals(policy.privateUrlPath())) {
                    return true;
                }
                return policy.publicUrlEnabled() && path.equals(policy.publicUrlPath());
            });
            if (exists) {
                throw new IllegalArgumentException("Access path already exists: " + path);
            }
        }
    }

    private String normalizeRoutePath(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeLogoDataUrl(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    private void validateLogoDataUrlOrThrow(String logoDataUrl) {
        if (logoDataUrl.length() > MAX_LOGO_DATA_URL_LENGTH) {
            throw new IllegalArgumentException("Logo image is too large.");
        }
        if (!DATA_URL_PATTERN.matcher(logoDataUrl).matches()) {
            throw new IllegalArgumentException("Logo must be an image data URL.");
        }
    }

    public record PublicSkillId(String groupId, String skillId) {}

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }
}
