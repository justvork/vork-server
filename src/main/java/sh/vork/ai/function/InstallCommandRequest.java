package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record InstallCommandRequest(
        @JsonProperty(required = true, value = "binPath")
        @JsonPropertyDescription("Relative directory containing command executables (typically under tools/...).")
        String binPath,

        @JsonProperty(required = false, value = "command")
        @JsonPropertyDescription("Optional command filename to verify exists in binPath, e.g. node or mvn.")
        String command,

        @JsonProperty(required = false, value = "area")
        @JsonPropertyDescription("Storage area for the install path. SESSION (default) is recommended.")
        String area
) {}
