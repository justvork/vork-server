package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record UploadTextFileRequest(
        @JsonProperty(required = true, value = "hostOrAlias")
        @JsonPropertyDescription("SSH host string or alias of an active connection established with connectSsh.")
        String hostOrAlias,

        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("Text content to write to the remote file. Written as UTF-8.")
        String content,

        @JsonProperty(required = true, value = "remotePath")
        @JsonPropertyDescription("Destination path on the remote host, including filename. E.g. /home/user/script.sh or ~/config.txt.")
        String remotePath
) {}
