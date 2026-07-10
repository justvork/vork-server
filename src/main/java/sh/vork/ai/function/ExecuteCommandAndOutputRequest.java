package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ExecuteCommandAndOutputRequest(
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("Shell command to execute synchronously.")
        String command
) {}
