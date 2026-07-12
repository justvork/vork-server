package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code getPublicKey} tool.
 */
public record GetPublicKeyRequest(
        @JsonProperty(required = true, value = "secretName")
        @JsonPropertyDescription("Secret key identifier used when generatePrivateKey was called. Supports plain names or {{secret.ref}} format.")
        String secretName
) {}
