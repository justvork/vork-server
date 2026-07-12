package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code signData} tool.
 */
public record SignDataRequest(
        @JsonProperty(required = true, value = "data")
        @JsonPropertyDescription("UTF-8 string data to sign.")
        String data,

        @JsonProperty(required = true, value = "privateKey")
        @JsonPropertyDescription("Private key in PKCS#8 DER Base64 or PEM format, or a secret reference like {{signing.key.main}} returned by generatePrivateKey.")
        String privateKey,

        @JsonProperty(required = true, value = "algorithm")
        @JsonPropertyDescription("Signature algorithm, for example SHA256withRSA or Ed25519.")
        String algorithm
) {}
