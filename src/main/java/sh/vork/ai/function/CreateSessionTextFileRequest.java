package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for creating a text file in the session sandbox or shared area.
 */
public record CreateSessionTextFileRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative file path inside the selected area, e.g. notes/output.md")
        String path,

        @JsonProperty(value = "content", required = true)
        @JsonPropertyDescription("UTF-8 text content to write")
        String content,

        @JsonProperty(value = "area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area
) {}
