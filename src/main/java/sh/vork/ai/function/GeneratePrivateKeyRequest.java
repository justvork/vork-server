package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code generatePrivateKey} tool.
 * Note: secretName is collected via an interactive form and handled separately.
 */
public record GeneratePrivateKeyRequest(
        @JsonProperty(value = "keyAlgorithm")
        @JsonPropertyDescription("Key algorithm. Supports RSA and ED25519. Defaults to RSA.")
        String keyAlgorithm,

        @JsonProperty(value = "keySize")
        @JsonPropertyDescription("Key size in bits for RSA only. Defaults to 2048. Ignored for ED25519.")
        Integer keySize
) {}
