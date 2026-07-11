package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FolderExistsRequest(
        @JsonProperty(required = true, value = "path")
        @JsonPropertyDescription("Relative folder path in the selected area.")
        String path,

        @JsonProperty(required = false, value = "area")
        @JsonPropertyDescription("Storage area: SESSION (default) or SHARED.")
        String area
) {}
