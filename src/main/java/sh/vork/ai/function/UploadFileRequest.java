package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record UploadFileRequest(
        @JsonProperty(required = true, value = "hostOrAlias")
        @JsonPropertyDescription("SSH host string or alias of an active connection established with connectSsh.")
        String hostOrAlias,

        @JsonProperty(required = true, value = "filename")
        @JsonPropertyDescription("Session file reference to upload (accepts 'session-url:/api/session-files/download?...', direct '/api/session-files/download?...', or a relative path in the current session area), or an absolute local filesystem path. Local paths require explicit authorisation.")
        String filename,

        @JsonProperty(required = false, value = "remotePath")
        @JsonPropertyDescription("Destination path on the remote host. If omitted, the file is uploaded to the remote home directory with its original name.")
        String remotePath
) {}
