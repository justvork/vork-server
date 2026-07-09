package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for zipping a folder and returning a download URL.
 */
public record DownloadFolderAsZipRequest(
        @JsonProperty(value = "folderPath", required = true)
        @JsonPropertyDescription("Relative directory path to zip, e.g. docs")
        String folderPath,

        @JsonProperty("outputZipPath")
        @JsonPropertyDescription("Optional output zip path, e.g. exports/docs.zip")
        String outputZipPath,

        @JsonProperty("area")
        @JsonPropertyDescription("Target area: SESSION (default) or SHARED")
        String area,

        @JsonProperty("attachToChat")
        @JsonPropertyDescription("Whether to attach the generated zip to the assistant message (default: true)")
        Boolean attachToChat,

        @JsonProperty("attachOnlyZip")
        @JsonPropertyDescription("When true (default), remove intermediate generated attachments and attach only the zip")
        Boolean attachOnlyZip
) {}
