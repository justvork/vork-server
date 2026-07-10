package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record WriteProcessRequest(
        @JsonProperty(required = true, value = "pid")
        @JsonPropertyDescription("Process identifier returned by startProcessTool.")
        String pid,

        @JsonProperty(required = true, value = "input")
        @JsonPropertyDescription("Text to write to the process stdin.")
        String input
) {}
