package sh.vork.ai.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.request.RequestResponsePolicy;
import sh.vork.orm.DatabaseRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Component
public class ApprovalPolicyRuntimeResolver {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPolicyRuntimeResolver.class);
    public static final String SESSION_APPROVAL_OVERRIDE_ENV = "SESSION_APPROVAL_OVERRIDE";

    private final ApprovalPolicyService approvalPolicyService;
    private final DatabaseRepository<AiSession> sessionRepository;
    private final ObjectMapper objectMapper;

    public ApprovalPolicyRuntimeResolver(ApprovalPolicyService approvalPolicyService,
                                         DatabaseRepository<AiSession> sessionRepository) {
        this(approvalPolicyService, sessionRepository, new ObjectMapper());
    }

    @Autowired
    public ApprovalPolicyRuntimeResolver(ApprovalPolicyService approvalPolicyService,
                                         DatabaseRepository<AiSession> sessionRepository,
                                         ObjectMapper objectMapper) {
        this.approvalPolicyService = approvalPolicyService;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    public ToolSuspensionException.SuspensionCampaign resolveCampaign(String sessionUuid, String toolName) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return null;
        }

        AiSession session = sessionRepository.get(sessionUuid);
        if (session == null) {
            return null;
        }

        SessionApprovalOverride sessionOverride = readSessionApprovalOverride(session);
        if (sessionOverride != null) {
            if (!sessionOverride.enabled()) {
                return null;
            }
            ToolSuspensionException.SuspensionCampaign sessionCampaign = resolveSessionOverrideCampaign(session, sessionOverride);
            if (sessionCampaign != null) {
                return sessionCampaign;
            }
        }

        String policyId = resolveAssignedPolicyId(session);
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        ApprovalPolicy policy = approvalPolicyService.getPolicy(policyId);
        if (policy == null || !policy.enabled()) {
            return null;
        }

        List<String> effectiveChannels = resolveEffectiveChannels(policy);
        if (effectiveChannels.isEmpty()) {
            return null;
        }

        return new ToolSuspensionException.SuspensionCampaign(
                effectiveChannels,
                RequestResponsePolicy.FIRST,
                null,
                true,
                "Approval required",
                "Approval policy route",
                null,
                null
        );
    }

    private ToolSuspensionException.SuspensionCampaign resolveSessionOverrideCampaign(AiSession session,
                                                                                       SessionApprovalOverride override) {
        List<String> channels;
        if (override.policyId() != null && !override.policyId().isBlank()) {
            ApprovalPolicy policy = approvalPolicyService.getPolicy(override.policyId());
            if (policy == null || !policy.enabled()) {
                log.warn("Session approval override references missing/disabled policy [session={}, policyId={}]",
                        session.uuid(), override.policyId());
                return null;
            }
            channels = resolveEffectiveChannels(policy);
        } else {
            channels = override.channels();
        }

        if (channels == null || channels.isEmpty()) {
            return null;
        }

        RequestResponsePolicy responsePolicy = override.responsePolicy() == null
                ? RequestResponsePolicy.FIRST
                : override.responsePolicy();
        Integer quorum = responsePolicy == RequestResponsePolicy.QUORUM ? normalizeQuorum(override.quorum()) : null;
        String rationale = override.reason() == null || override.reason().isBlank()
                ? "Session approval override"
                : override.reason();

        return new ToolSuspensionException.SuspensionCampaign(
                List.copyOf(channels),
                responsePolicy,
                quorum,
                true,
                "Approval required",
                rationale,
                null,
                null
        );
    }

    private SessionApprovalOverride readSessionApprovalOverride(AiSession session) {
        Map<String, String> env = session.environmentVariables();
        if (env == null || env.isEmpty()) {
            return null;
        }
        String raw = env.get(SESSION_APPROVAL_OVERRIDE_ENV);
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(raw);
            boolean enabled = !node.has("enabled") || node.path("enabled").asBoolean(true);
            String policyId = readText(node, "policyId");
            String reason = readText(node, "reason");
            RequestResponsePolicy responsePolicy = parseResponsePolicy(readText(node, "responsePolicy"));
            Integer quorum = node.has("quorum") && node.path("quorum").canConvertToInt()
                    ? node.path("quorum").asInt()
                    : null;

            List<String> channels = List.of();
            JsonNode channelsNode = node.path("channels");
            if (channelsNode.isArray()) {
                channels = java.util.stream.StreamSupport.stream(channelsNode.spliterator(), false)
                        .filter(JsonNode::isTextual)
                        .map(JsonNode::asText)
                        .map(v -> v == null ? "" : v.trim())
                        .filter(v -> !v.isBlank())
                        .distinct()
                        .toList();
            }
            return new SessionApprovalOverride(enabled, policyId, channels, responsePolicy, quorum, reason);
        } catch (Exception ex) {
            log.warn("Ignoring invalid session approval override payload [session={}, error={}]",
                    session.uuid(), ex.getMessage());
            return null;
        }
    }

    private static String readText(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        String value = node.path(field).asText(null);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static RequestResponsePolicy parseResponsePolicy(String value) {
        if (value == null || value.isBlank()) {
            return RequestResponsePolicy.FIRST;
        }
        try {
            RequestResponsePolicy parsed = RequestResponsePolicy.valueOf(value.trim().toUpperCase());
            if (parsed == RequestResponsePolicy.AUTO) {
                return RequestResponsePolicy.FIRST;
            }
            return parsed;
        } catch (Exception ignored) {
            return RequestResponsePolicy.FIRST;
        }
    }

    private static Integer normalizeQuorum(Integer quorum) {
        if (quorum == null || quorum < 1) {
            return 1;
        }
        return quorum;
    }

    private String resolveAssignedPolicyId(AiSession session) {
        String skillPolicy = resolveSkillPolicy(session);
        if (skillPolicy != null && !skillPolicy.isBlank()) {
            return skillPolicy;
        }

        String agentId = session.activeAgentTemplateId();
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        Map<String, String> assignments = approvalPolicyService.listAssignmentsByTargetType(ApprovalPolicyAssignment.TARGET_AGENT);
        return assignments.get(agentId);
    }

    private String resolveSkillPolicy(AiSession session) {
        if (session.skillStack() == null || session.skillStack().isEmpty()) {
            return null;
        }
        String skillUuid = session.skillStack().get(session.skillStack().size() - 1).skillUuid();
        if (skillUuid == null || skillUuid.isBlank()) {
            return null;
        }
        Map<String, String> assignments = approvalPolicyService.listAssignmentsByTargetType(ApprovalPolicyAssignment.TARGET_SKILL);
        return assignments.get(skillUuid);
    }

    private List<String> resolveEffectiveChannels(ApprovalPolicy policy) {
        LocalDate nowDate = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        DayOfWeek nowDay = nowDate.getDayOfWeek();

        ApprovalPolicyOverride selected = null;
        for (ApprovalPolicyOverride candidate : policy.overrides()) {
            if (candidate == null || !candidate.enabled()) {
                continue;
            }
            if (!matchesDay(candidate.day(), nowDay)) {
                continue;
            }
            if (!matchesTime(candidate.startTime(), candidate.endTime(), nowTime)) {
                continue;
            }
            selected = candidate;
            break;
        }

        if (selected != null && selected.channels() != null && !selected.channels().isEmpty()) {
            return List.copyOf(selected.channels());
        }
        return policy.channels() == null ? List.of() : List.copyOf(policy.channels());
    }

    private static boolean matchesDay(String configuredDay, DayOfWeek day) {
        if (configuredDay == null || configuredDay.isBlank()) {
            return true;
        }
        return day.name().equalsIgnoreCase(configuredDay.trim());
    }

    private static boolean matchesTime(String startRaw, String endRaw, LocalTime currentTime) {
        LocalTime start = parseTime(startRaw);
        LocalTime end = parseTime(endRaw);
        if (start == null && end == null) {
            return true;
        }
        if (start != null && end == null) {
            return !currentTime.isBefore(start);
        }
        if (start == null) {
            return !currentTime.isAfter(end);
        }

        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !currentTime.isBefore(start) && !currentTime.isAfter(end);
        }
        // Overnight window, e.g. 22:00-06:00.
        return !currentTime.isBefore(start) || !currentTime.isAfter(end);
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private record SessionApprovalOverride(boolean enabled,
                                           String policyId,
                                           List<String> channels,
                                           RequestResponsePolicy responsePolicy,
                                           Integer quorum,
                                           String reason) {
    }

}
