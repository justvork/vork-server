package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code summarizeNotificationLedger} tool.
 */
public record SummarizeNotificationLedgerRequest(

        @JsonProperty(value = "sinceEpochMillis")
        @JsonPropertyDescription("Optional lower bound for createdAt (inclusive), in epoch milliseconds.")
        Long sinceEpochMillis,

        @JsonProperty(value = "providerConfigId")
        @JsonPropertyDescription("Optional provider configuration UUID filter.")
        String providerConfigId,

        @JsonProperty(value = "idempotencyGroup")
        @JsonPropertyDescription("Optional idempotency group filter.")
        String idempotencyGroup,

        @JsonProperty(value = "destination")
        @JsonPropertyDescription("Optional destination filter (email address or phone number).")
        String destination
) {}
