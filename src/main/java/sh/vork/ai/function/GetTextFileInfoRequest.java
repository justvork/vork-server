package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GetTextFileInfoRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative path to a text/log file in SESSION or SHARED area.")
        String path,

        @JsonProperty(value = "area", required = false)
        @JsonPropertyDescription("Storage area: SESSION (default) or SHARED.")
        String area
) {}
