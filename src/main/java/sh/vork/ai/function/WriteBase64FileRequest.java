package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for writing a Base64-encoded binary file in the session/shared file system.
 */
public record WriteBase64FileRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative file path to write, e.g. attachments/invoice.pdf")
        String path,

        @JsonProperty(value = "base64Content", required = true)
        @JsonPropertyDescription("Base64-encoded binary file content (standard or URL-safe Base64)")
        String base64Content,

        @JsonProperty("area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area,

        @JsonProperty("attachToChat")
        @JsonPropertyDescription("Whether to attach this generated file to the assistant message (default: true)")
        Boolean attachToChat
) {}
