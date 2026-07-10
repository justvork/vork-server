package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record StopProcessRequest(
        @JsonProperty(required = true, value = "pid")
        @JsonPropertyDescription("Process identifier returned by startProcessTool.")
        String pid
) {}
