package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record StartProcessRequest(
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("Shell command to launch as a background process.")
        String command
) {}
