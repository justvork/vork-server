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

        @JsonProperty(required = true, value = "jobUuid")
        @JsonPropertyDescription("UUID of the preconfigured scheduled job artifact to delegate. This job must be assigned to the selected agent.")
        String jobUuid,

        @JsonProperty(required = true, value = "prompt")
        @JsonPropertyDescription("Complete task prompt including all data required for the delegated background task. This overrides the assigned job prompt for this delegated run.")
        String prompt
) {}
