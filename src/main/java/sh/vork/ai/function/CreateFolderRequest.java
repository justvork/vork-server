package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for creating a folder in the session/shared file system.
 */
public record CreateFolderRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative directory path to create, e.g. docs/releases")
        String path,

        @JsonProperty("area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area
) {}
