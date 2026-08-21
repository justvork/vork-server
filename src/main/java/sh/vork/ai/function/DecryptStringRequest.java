package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code decryptString} tool.
 */
public record DecryptStringRequest(
        @JsonProperty(required = true, value = "input")
        @JsonPropertyDescription("Encrypted text input to decrypt.")
        String input,

        @JsonProperty(value = "privateKeyPath")
        @JsonPropertyDescription(
                "Optional session-accessible file path to an RSA private key (PKCS#8) for legacy key-path mode. "
                        + "If set, publicKeyPath must also be set.")
        String privateKeyPath,

        @JsonProperty(value = "publicKeyPath")
        @JsonPropertyDescription(
                "Optional session-accessible file path to an RSA public key (X.509) for legacy key-path mode. "
                        + "If set, privateKeyPath must also be set.")
        String publicKeyPath
) {}
