package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record IsCommandInstalledRequest(
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("Command name to locate in registered session command paths, e.g. node or mvn.")
        String command
) {}
