package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code exportJavaTypeSource} function tool.
 */
public record ExportJavaTypeSourceRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the custom runtime-compiled type")
        String fqn
) {}
