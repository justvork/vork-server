package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code recordProgress} meta-tool.
 *
 * <p>The AI calls this to persist an execution checkpoint into the current
 * session's environment variables so it can recover state on later turns.
 */
public record RecordProgressRequest(
        @JsonProperty(required = true, value = "entry")
        @JsonPropertyDescription("A concise progress checkpoint to persist for future turns, e.g. 'Scanned 10.0.22.10'.")
        String entry
) {}
