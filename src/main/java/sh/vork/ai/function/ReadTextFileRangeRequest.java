package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ReadTextFileRangeRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative path to the text/log file to read.")
        String path,

        @JsonProperty(value = "startLine", required = true)
        @JsonPropertyDescription("Inclusive 1-based start line.")
        Long startLine,

        @JsonProperty(value = "endLine", required = true)
        @JsonPropertyDescription("Inclusive 1-based end line.")
        Long endLine,

        @JsonProperty(value = "area", required = false)
        @JsonPropertyDescription("Storage area: SESSION (default) or SHARED.")
        String area
) {}
