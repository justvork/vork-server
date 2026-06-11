package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code deleteSshConnection} tool.
 */
public record DeleteSshConnectionRequest(
        @JsonProperty(required = true, value = "hostOrAlias")
        @JsonPropertyDescription("The alias or hostname of the SSH connection to permanently delete from Vork nodes.")
        String hostOrAlias
) {}
