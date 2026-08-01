package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for the {@code getOauthTemplate} tool.
 */
public record GetOauthTemplateRequest(

        @JsonProperty(required = true, value = "clientName")
        @JsonPropertyDescription("Logical OAuth template client name, e.g. google_calendar.")
        String clientName

) {}
