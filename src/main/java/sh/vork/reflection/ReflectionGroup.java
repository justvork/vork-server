package sh.vork.reflection;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import sh.vork.orm.DatabaseEntity;
import sh.vork.skill.SkillSecret;

/**
 * Group metadata for related reflections.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReflectionGroup(
        String uuid,
    String toolId,
        String name,
        String description,
        ReflectionType type,
    String baseUrl,
    Boolean urlOverrideEnabled,
    List<SkillSecret> bindingSecrets,
    List<ReflectionBindingParameter> bindingParameters,
    ReflectionAuthenticationMode authenticationMode,
    String oauthTemplateId,
        long version,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public ReflectionGroup {
        if (name == null || name.isBlank()) {
            name = "Unnamed Group";
        }
        if (toolId == null || toolId.isBlank()) {
            toolId = normalizeToolId(name);
        } else {
            toolId = normalizeToolId(toolId);
        }
        if (description == null) {
            description = "";
        }
        if (type == null) {
            type = ReflectionType.REST;
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (urlOverrideEnabled == null) {
            urlOverrideEnabled = Boolean.TRUE;
        }
        if (bindingSecrets == null) {
            bindingSecrets = List.of();
        }
        if (bindingParameters == null) {
            bindingParameters = List.of();
        }
        if (authenticationMode == null) {
            authenticationMode = ReflectionAuthenticationMode.NONE;
        }
        if (authenticationMode == ReflectionAuthenticationMode.NONE) {
            oauthTemplateId = "";
        }
        if (oauthTemplateId == null) {
            oauthTemplateId = "";
        } else {
            oauthTemplateId = oauthTemplateId.trim();
        }
        if (version < 1) {
            version = 1;
        }
    }

    private static String normalizeToolId(String source) {
        StringBuilder sb = new StringBuilder();
        String raw = source == null ? "" : source.trim().toLowerCase();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        String normalized = sb.toString();
        if (normalized.isBlank()) {
            return "group";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            return "g" + normalized;
        }
        return normalized;
    }
}
