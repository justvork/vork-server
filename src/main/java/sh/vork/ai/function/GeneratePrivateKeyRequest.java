package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code generatePrivateKey} tool.
 */
public record GeneratePrivateKeyRequest(
        @JsonProperty(required = true, value = "secretName")
        @JsonPropertyDescription("Secret key name used to store the generated private key.")
        String secretName,

        @JsonProperty(value = "keyAlgorithm")
        @JsonPropertyDescription("Key algorithm. Supports RSA and ED25519. Defaults to RSA.")
        String keyAlgorithm,

        @JsonProperty(value = "keySize")
        @JsonPropertyDescription("Key size in bits for RSA only. Defaults to 2048. Ignored for ED25519.")
        Integer keySize
) {}
