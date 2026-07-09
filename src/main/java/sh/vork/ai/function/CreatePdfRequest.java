package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for creating a PDF from markdown or HTML and storing it in session/shared files.
 */
public record CreatePdfRequest(
        @JsonProperty(value = "content", required = true)
        @JsonPropertyDescription("Source content to convert into PDF")
        String content,

        @JsonProperty("format")
        @JsonPropertyDescription("Source format: MARKDOWN (default) or HTML")
        String format,

        @JsonProperty("outputPath")
        @JsonPropertyDescription("Output PDF path, e.g. reports/summary.pdf (default: generated.pdf)")
        String outputPath,

        @JsonProperty("area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area,

        @JsonProperty("attachToChat")
        @JsonPropertyDescription("Whether to attach this generated PDF to the assistant message (default: true)")
        Boolean attachToChat
) {}
