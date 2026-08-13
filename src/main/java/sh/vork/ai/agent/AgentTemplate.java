package sh.vork.ai.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import sh.vork.orm.DatabaseEntity;

import java.util.List;

/**
 * A named AI agent configuration stored in MongoDB.
 *
 * <p>An {@code AgentTemplate} defines the persona and capability boundary for an
 * agent that can be activated within an {@link sh.vork.ai.entity.AiSession}.
 * When a session has an active agent template the orchestration layer injects the
 * template's {@code systemPrompt} into every request and restricts available tools
 * to the {@code allowedTools} list.
 *
 * @param uuid         unique document ID (MongoDB {@code _id})
 * @param name         human-friendly label shown in management UIs
 * @param systemPrompt directives prepended to the system prompt for this agent
 * @param allowedTools Spring bean IDs of the {@code ToolCallback} beans this
 *                     agent may invoke; an empty list means no tool restriction
 *                     is applied (all tools available)
 * @param systemAgent  {@code true} for built-in agents that must not be deleted
 * @param skillUuids   UUIDs of {@link sh.vork.skill.Skill} records this agent
 *                     is permitted to invoke; triggers auto-injection of the
 *                     {@code executeSkill} tool into the allowed-tools list
 * @param agentType    operational context: {@link AgentType#INTERACTIVE} for chat agents,
 *                     {@link AgentType#BACKGROUND} for scheduled-job automation agents
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentTemplate(
        String       uuid,
        String       name,
        String       systemPrompt,
        List<String> allowedTools,
        boolean      systemAgent,
        List<String> skillUuids,
    AgentType    agentType,
    List<String> bindingUuids,
    List<String> assignedUsernames,
    List<String> jobUuids,
    String       recommendedModel,
    String       groupId,
    String       artifactId,
    String       version,
    ArtifactStatus artifactStatus
) implements DatabaseEntity {

    private static final String DEFAULT_VERSION = "SNAPSHOT";

    public AgentTemplate {
        if (name == null || name.isBlank()) {
            name = "Unnamed Agent";
        }
        if (systemPrompt == null) {
            systemPrompt = "";
        }
        if (allowedTools == null) {
            allowedTools = List.of();
        }
        if (skillUuids == null) {
            skillUuids = List.of();
        }
        if (agentType == null) {
            agentType = AgentType.INTERACTIVE;
        }
        if (bindingUuids == null || bindingUuids.isEmpty()) {
            bindingUuids = List.of();
        } else {
            java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
            for (String bindingUuid : bindingUuids) {
                if (bindingUuid == null || bindingUuid.isBlank()) {
                    continue;
                }
                normalized.add(bindingUuid.trim());
            }
            bindingUuids = List.copyOf(normalized);
        }
        if (assignedUsernames == null || assignedUsernames.isEmpty()) {
            assignedUsernames = List.of();
        } else {
            java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
            for (String username : assignedUsernames) {
                if (username == null || username.isBlank()) {
                    continue;
                }
                normalized.add(username.trim());
            }
            assignedUsernames = List.copyOf(normalized);
        }
        if (jobUuids == null || jobUuids.isEmpty()) {
            jobUuids = List.of();
        } else {
            java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
            for (String jobUuid : jobUuids) {
                if (jobUuid == null || jobUuid.isBlank()) {
                    continue;
                }
                normalized.add(jobUuid.trim());
            }
            jobUuids = List.copyOf(normalized);
        }
        if (recommendedModel != null) {
            String normalized = recommendedModel.trim();
            if (normalized.isBlank()) {
                recommendedModel = null;
            } else {
                int sep = normalized.indexOf(':');
                if (sep > 0) {
                    String provider = normalized.substring(0, sep).trim().toUpperCase();
                    String modelId = normalized.substring(sep + 1).trim();
                    recommendedModel = provider + ":" + modelId;
                } else {
                    recommendedModel = normalized.toUpperCase();
                }
            }
        }

        if (systemAgent) {
            groupId = null;
            artifactId = null;
            version = null;
            artifactStatus = null;
        } else {
            groupId = normalizeIdentifier(groupId);
            artifactId = normalizeIdentifier(artifactId);
            version = normalizeVersion(version);
            artifactStatus = artifactStatus == null ? ArtifactStatus.SNAPSHOT : artifactStatus;
        }
    }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType) {
                this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
                    List.of(), List.of(), List.of(), null, null, null, null, null);
    }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType,
                         List<String> bindingUuids) {
                this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
                    bindingUuids, List.of(), List.of(), null, null, null, null, null);
    }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType,
                         List<String> bindingUuids,
                         List<String> assignedUsernames) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
            bindingUuids, assignedUsernames, List.of(), null, null, null, null, null);
    }

        public AgentTemplate(String uuid,
                 String name,
                 String systemPrompt,
                 List<String> allowedTools,
                 boolean systemAgent,
                 List<String> skillUuids,
                 AgentType agentType,
                 List<String> bindingUuids,
                 List<String> assignedUsernames,
                 List<String> jobUuids,
                 String recommendedModel) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
            bindingUuids, assignedUsernames, jobUuids, recommendedModel, null, null, null,
            systemAgent ? null : ArtifactStatus.SNAPSHOT);
        }

        public AgentTemplate(String uuid,
                 String name,
                 String systemPrompt,
                 List<String> allowedTools,
                 boolean systemAgent,
                 List<String> skillUuids,
                 AgentType agentType,
                 List<String> bindingUuids,
                 List<String> assignedUsernames,
                 String recommendedModel) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
            bindingUuids, assignedUsernames, List.of(), recommendedModel, null, null, null,
            systemAgent ? null : ArtifactStatus.SNAPSHOT);
        }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType,
                         List<String> bindingUuids,
                         List<String> assignedUsernames,
                         List<String> jobUuids,
                         String recommendedModel,
                         String groupId,
                         String artifactId,
                         String version) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
                    bindingUuids, assignedUsernames, jobUuids, recommendedModel, groupId, artifactId, version,
                systemAgent ? null : ArtifactStatus.SNAPSHOT);
    }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType,
                         List<String> bindingUuids,
                         List<String> assignedUsernames,
                         String recommendedModel,
                         String groupId,
                         String artifactId,
                         String version,
                         ArtifactStatus artifactStatus) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
                bindingUuids, assignedUsernames, List.of(), recommendedModel, groupId, artifactId, version,
                artifactStatus);
    }

    private static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_VERSION;
        }
        return raw.trim();
    }

    @JsonIgnore
    public boolean isSnapshotMutable() {
        return !systemAgent && (artifactStatus == null || artifactStatus == ArtifactStatus.SNAPSHOT);
    }

    @JsonIgnore
    public boolean isDeletable() {
        if (systemAgent) {
            return false;
        }
        return artifactStatus == null
                || artifactStatus == ArtifactStatus.SNAPSHOT
                || artifactStatus == ArtifactStatus.SUBMITTED
                || artifactStatus == ArtifactStatus.REJECTED;
    }
}
