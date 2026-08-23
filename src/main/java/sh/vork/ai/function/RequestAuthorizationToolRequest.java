package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record RequestAuthorizationToolRequest(
        @JsonProperty(required = true, value = "toolName")
        @JsonPropertyDescription("Required. Restricted tool name to pre-authorize.")
        String toolName,

        @JsonProperty(required = true, value = "argumentsJson")
        @JsonPropertyDescription("Required. Exact JSON argument payload that will be executed for the restricted tool.")
        String argumentsJson,

        @JsonPropertyDescription("Optional scope for the token. Valid values: SESSION or BACKGROUND. Defaults to SESSION.")
        String scope,

        @JsonPropertyDescription("Optional token lifetime in seconds. Defaults to 900; max 3600.")
        Integer ttlSeconds,

        @JsonPropertyDescription("Optional human-friendly reason shown in audit records.")
        String reason
) {
}
