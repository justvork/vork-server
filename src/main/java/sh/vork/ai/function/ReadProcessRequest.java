package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ReadProcessRequest(
        @JsonProperty(required = true, value = "pid")
        @JsonPropertyDescription("Process identifier returned by startProcessTool.")
        String pid,

        @JsonProperty(required = false, value = "timeoutSeconds")
        @JsonPropertyDescription("Optional timeout in seconds while waiting for new output. Defaults to 0.")
        Integer timeoutSeconds
) {}
