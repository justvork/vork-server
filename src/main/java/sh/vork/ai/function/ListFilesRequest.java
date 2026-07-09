package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for listing files from the session/shared file system.
 */
public record ListFilesRequest(
        @JsonProperty("path")
        @JsonPropertyDescription("Relative directory to list (blank for root)")
        String path,

        @JsonProperty("area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area
) {}
