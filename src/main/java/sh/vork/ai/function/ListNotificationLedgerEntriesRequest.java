package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code listNotificationLedgerEntries} tool.
 */
public record ListNotificationLedgerEntriesRequest(

        @JsonProperty(value = "page")
        @JsonPropertyDescription("Zero-based page index. Defaults to 0.")
        Integer page,

        @JsonProperty(value = "pageSize")
        @JsonPropertyDescription("Maximum entries per page. Defaults to 50.")
        Integer pageSize,

        @JsonProperty(value = "finalState")
        @JsonPropertyDescription("Optional final state filter: SENT, FAILED, ALREADY_SENT.")
        String finalState,

        @JsonProperty(value = "idempotencyKey")
        @JsonPropertyDescription("Optional exact idempotency key filter.")
        String idempotencyKey,

        @JsonProperty(value = "destination")
        @JsonPropertyDescription("Optional destination filter (email address or phone number).")
        String destination,

        @JsonProperty(value = "providerConfigId")
        @JsonPropertyDescription("Optional provider configuration UUID filter.")
        String providerConfigId
) {}
