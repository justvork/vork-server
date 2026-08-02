package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the dynamic {@code delegateTask} tool.
 */
public record DelegateTaskRequest(
        @JsonProperty(required = true, value = "agentName")
        @JsonPropertyDescription("Exact display name of the background agent template to run.")
        String agentName,

        @JsonProperty(required = true, value = "prompt")
        @JsonPropertyDescription("Complete task prompt including all data required for the delegated background task.")
        String prompt
) {}
