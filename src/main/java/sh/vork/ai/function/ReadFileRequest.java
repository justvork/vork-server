package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for reading a file from the session/shared file system.
 */
public record ReadFileRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative file path to read")
        String path,

        @JsonProperty("area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area,

        @JsonProperty("maxBytes")
        @JsonPropertyDescription("Maximum bytes to read before truncation (default 200000)")
        Integer maxBytes
) {}
