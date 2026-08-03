package sh.vork.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.lifecycle.AgentTemplateSeeder;
import sh.vork.orm.DatabaseRepository;
import sh.vork.security.UserRole;
import sh.vork.security.VorkUser;

/**
 * Resolves whether an agent is selectable/executable for a specific user.
 */
@Service
public class AgentAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AgentAssignmentService.class);

    private final DatabaseRepository<AgentTemplate> agentTemplateRepository;
    private final DatabaseRepository<VorkUser> userRepository;

    public AgentAssignmentService(DatabaseRepository<AgentTemplate> agentTemplateRepository,
                                  DatabaseRepository<VorkUser> userRepository) {
        this.agentTemplateRepository = agentTemplateRepository;
        this.userRepository = userRepository;
    }

    public boolean isAdminUser(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        VorkUser user = userRepository.get(username.trim());
        return user != null && UserRole.fromStoredValue(user.role()) == UserRole.ADMIN;
    }

    public boolean isConciergeAgent(AgentTemplate template) {
        if (template == null) {
            return false;
        }
        if (AgentTemplateSeeder.UUID_CONCIERGE.equals(template.uuid())) {
            return true;
        }
        return template.name() != null && "Concierge".equalsIgnoreCase(template.name().trim());
    }

    public boolean isAssignedToUser(AgentTemplate template, String username) {
        if (template == null || username == null || username.isBlank()) {
            return false;
        }
        if (isConciergeAgent(template)) {
            return true;
        }
        if (isAdminUser(username)) {
            return true;
        }

        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        return (template.assignedUsernames() == null ? List.<String>of() : template.assignedUsernames())
                .stream()
                .filter(u -> u != null && !u.isBlank())
                .map(u -> u.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalizedUsername::equals);
    }

    public List<AgentTemplate> listAssignedAgentsForUser(String username, AgentType filterType) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        try (var stream = agentTemplateRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(template -> filterType == null || template.agentType() == filterType)
                    .filter(template -> isAssignedToUser(template, username))
                    .toList();
        }
    }

    public AgentTemplate resolveAssignedAgentById(String username, String agentTemplateId) {
        if (agentTemplateId == null || agentTemplateId.isBlank()) {
            return null;
        }
        AgentTemplate template = agentTemplateRepository.get(agentTemplateId.trim());
        if (template == null) {
            return null;
        }
        if (!isAssignedToUser(template, username)) {
            log.warn("Agent access denied [user={}, agentId={}, agentName={}]", username, template.uuid(), template.name());
            return null;
        }
        return template;
    }

    public AgentTemplate resolveAssignedAgentByName(String username, String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }
        String target = agentName.trim();
        return listAssignedAgentsForUser(username, null).stream()
                .filter(template -> template.name() != null && template.name().equalsIgnoreCase(target))
                .findFirst()
                .orElse(null);
    }

    public List<String> normalizeAndValidateAssignedUsernames(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }

        List<String> unknownUsers = new ArrayList<>();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : usernames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String username = raw.trim();
            if (userRepository.get(username) == null) {
                unknownUsers.add(username);
                continue;
            }
            normalized.add(username);
        }

        if (!unknownUsers.isEmpty()) {
            throw new IllegalArgumentException("Unknown user(s) in assignedUsernames: " + String.join(", ", unknownUsers));
        }

        return List.copyOf(normalized);
    }
}
