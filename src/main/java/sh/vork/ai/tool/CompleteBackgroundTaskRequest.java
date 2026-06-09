package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record CompleteBackgroundTaskRequest(
        @JsonProperty(required = false, value = "sessionUuid")
        @JsonPropertyDescription("Optional session UUID override. Normally inferred from execution context.")
        String sessionUuid,

        @JsonProperty(required = true, value = "success")
        @JsonPropertyDescription("true if the task completed its objectives successfully (including producing any required output), false if it failed or only partially succeeded.")
        boolean success,

        @JsonProperty(required = true, value = "report")
        @JsonPropertyDescription("A concise summary of what was done, what was produced, and any notable outcomes or errors. If an expected output was defined, confirm whether it was met. This is stored as the permanent job result.")
        String report
) {}
