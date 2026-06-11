package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code completeSkillExecution} hidden tool.
 *
 * <p>The skill AI invokes this tool to signal that it has finished, providing
 * its output and a success flag.
 */
public record CompleteSkillExecutionRequest(

        @JsonProperty(required = true, value = "success")
        @JsonPropertyDescription("Whether the skill completed its objective successfully")
        boolean success,

        @JsonProperty(required = true, value = "output")
        @JsonPropertyDescription("The skill output to return to the calling session")
        String output
) {}
