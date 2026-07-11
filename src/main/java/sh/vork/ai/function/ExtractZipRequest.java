package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ExtractZipRequest(
        @JsonProperty(required = true, value = "archivePath")
        @JsonPropertyDescription("Path to the source zip file in the selected area.")
        String archivePath,

        @JsonProperty(required = true, value = "destinationPath")
        @JsonPropertyDescription("Destination folder path where zip contents are extracted.")
        String destinationPath,

        @JsonProperty(required = false, value = "area")
        @JsonPropertyDescription("Storage area: SESSION (default) or SHARED.")
        String area,

        @JsonProperty(required = false, value = "attachToChat")
        @JsonPropertyDescription("Whether extracted files should be attached to chat output. Defaults to false.")
        Boolean attachToChat
) {}
