package sh.vork.ai.agent;

import sh.vork.orm.DatabaseEntity;
import sh.vork.reflection.ReflectionBindingAssignment;

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
public record AgentTemplate(
        String       uuid,
        String       name,
        String       systemPrompt,
        List<String> allowedTools,
        boolean      systemAgent,
        List<String> skillUuids,
    AgentType    agentType,
    List<ReflectionBindingAssignment> reflectionBindings,
    List<String> assignedUsernames,
    String       recommendedModel
) implements DatabaseEntity {

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
        if (reflectionBindings == null) {
            reflectionBindings = List.of();
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
    }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType, List.of(), List.of(), null);
    }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType,
                         List<ReflectionBindingAssignment> reflectionBindings) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType, reflectionBindings, List.of(), null);
    }

    public AgentTemplate(String uuid,
                         String name,
                         String systemPrompt,
                         List<String> allowedTools,
                         boolean systemAgent,
                         List<String> skillUuids,
                         AgentType agentType,
                         List<ReflectionBindingAssignment> reflectionBindings,
                         List<String> assignedUsernames) {
        this(uuid, name, systemPrompt, allowedTools, systemAgent, skillUuids, agentType,
                reflectionBindings, assignedUsernames, null);
    }
}
