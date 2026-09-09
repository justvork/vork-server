package sh.vork.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.security.SecuredToolCallback;
import sh.vork.orm.DatabaseRepository;

/**
 * Persists one TOOL chat record for each tool invocation in a live chat session.
 */
@Service
public class ToolInvocationPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ToolInvocationPersistenceService.class);

    private final DatabaseRepository<AiSession> sessionRepo;
    private final ObjectMapper objectMapper;

    public ToolInvocationPersistenceService(DatabaseRepository<AiSession> sessionRepo,
                                            ObjectMapper objectMapper) {
        this.sessionRepo = sessionRepo;
        this.objectMapper = objectMapper;
    }

    public void persistInvocation(String toolName,
                                  String arguments,
                                  String responseData,
                                  boolean success,
                                  boolean suspended,
                                  String errorType,
                                  String errorMessage,
                                  long durationMs) {
        String sessionUuid = ToolExecutionContext.getSessionUuid();
        if (sessionUuid == null || sessionUuid.isBlank() || "system".equalsIgnoreCase(sessionUuid)) {
            return;
        }
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        if ("readChatHistory".equals(toolName)) {
            return;
        }

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            log.warn("Cannot persist tool invocation: session not found [session={}, tool={}]", sessionUuid, toolName);
            return;
        }

        String toolCallId = resolveToolCallId();
        String payload = buildPayload(toolCallId, toolName, arguments, responseData, success, suspended,
                errorType, errorMessage, durationMs);
        String messageUuid = UUID.randomUUID().toString();

        List<AiChatMessage> updated = new ArrayList<>(session.messages() == null ? List.of() : session.messages());
        updated.add(new AiChatMessage(
            messageUuid,
                "TOOL",
                payload,
                System.currentTimeMillis(),
                null,
                null,
                toolCallId,
                toolName));

        sessionRepo.save(new AiSession(
                session.uuid(), session.provider(), session.originMode(),
                session.username(), session.name(), session.createdAt(),
                session.currentRoundCount(), List.copyOf(updated),
                session.environmentVariables(), session.status(),
                session.activeAgentTemplateId(), session.modelId(), session.skillStack(),
                session.sessionSkillUuids(), session.sessionToolIds()));

        log.debug("Persisted tool invocation [session={}, messageUuid={}, tool={}, toolCallId={}, success={}, suspended={}]",
            sessionUuid, messageUuid, toolName, toolCallId, success, suspended);
    }

    private String resolveToolCallId() {
        Object fromContext = ToolExecutionContext.get(SecuredToolCallback.CURRENT_TOOL_CALL_ID_CONTEXT_KEY);
        if (fromContext == null) {
            return "pending-" + UUID.randomUUID();
        }
        String value = String.valueOf(fromContext).trim();
        return value.isBlank() ? "pending-" + UUID.randomUUID() : value;
    }

    private String buildPayload(String toolCallId,
                                String toolName,
                                String arguments,
                                String responseData,
                                boolean success,
                                boolean suspended,
                                String errorType,
                                String errorMessage,
                                long durationMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "TOOL_RESPONSE");
        payload.put("responses", List.of(Map.of(
                "id", toolCallId,
                "name", toolName,
                "responseData", responseData == null ? "" : responseData)));
        payload.put("arguments", arguments == null || arguments.isBlank() ? "{}" : arguments);
        payload.put("success", success);
        payload.put("suspended", suspended);
        payload.put("durationMs", durationMs);
        if (!success) {
            payload.put("errorType", errorType == null ? "RuntimeException" : errorType);
            payload.put("error", errorMessage == null ? "Tool invocation failed" : errorMessage);
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"type\":\"TOOL_RESPONSE\",\"responses\":[{\"id\":\""
                    + toolCallId.replace("\"", "'")
                    + "\",\"name\":\""
                    + toolName.replace("\"", "'")
                    + "\",\"responseData\":\"\"}],\"arguments\":\"{}\"}";
        }
    }
}