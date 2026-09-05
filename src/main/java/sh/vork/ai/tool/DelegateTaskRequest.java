package sh.vork.ai.tool;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the dynamic {@code delegateTask} tool.
 */
public record DelegateTaskRequest(
        @JsonProperty(required = true, value = "agentName")
        @JsonPropertyDescription("Exact display name of the background agent template to run.")
        String agentName,

        @JsonProperty(required = false, value = "jobUuid")
        @JsonPropertyDescription("Optional UUID of a preconfigured scheduled job artifact. If omitted, a dynamic one-off delegated task is created and executed immediately.")
        String jobUuid,

        @JsonProperty(required = true, value = "prompt")
        @JsonPropertyDescription("Complete task prompt including all data required for the delegated background task. This overrides the assigned job prompt for this delegated run.")
        String prompt,

        @JsonProperty(required = false, value = "sessionFiles")
        @JsonPropertyDescription("Optional list of session file paths to copy into the delegated background job session before execution. Paths may be relative to the current session sandbox or session download URLs.")
        List<String> sessionFiles
) {}
