package sh.vork.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;

abstract class AbstractProcessTool {

    private final ObjectMapper objectMapper;

    protected AbstractProcessTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }

    protected String error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ERROR");
        payload.put("message", message);
        return json(payload);
    }

    protected String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"status\":\"ERROR\",\"message\":\"Serialization failed\"}";
        }
    }
}
