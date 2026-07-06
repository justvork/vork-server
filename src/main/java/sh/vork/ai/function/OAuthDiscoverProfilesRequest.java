package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for the {@code oauthDiscoverProfiles} tool.
 */
public record OAuthDiscoverProfilesRequest(

        @JsonProperty(required = true, value = "clientName")
        @JsonPropertyDescription("Logical OAuth client name, e.g. google_calendar, github, xero.")
        String clientName

) {}
