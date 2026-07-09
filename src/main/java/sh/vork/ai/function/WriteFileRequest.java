package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for writing a UTF-8 file in the session/shared file system.
 */
public record WriteFileRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative file path to write, e.g. notes/summary.md")
        String path,

        @JsonProperty(value = "content", required = true)
        @JsonPropertyDescription("UTF-8 text content to write")
        String content,

        @JsonProperty("area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area,

        @JsonProperty("attachToChat")
        @JsonPropertyDescription("Whether to attach this generated file to the assistant message (default: true)")
        Boolean attachToChat
) {}
