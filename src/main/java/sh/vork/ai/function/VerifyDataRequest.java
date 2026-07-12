package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code verifyData} tool.
 */
public record VerifyDataRequest(
        @JsonProperty(required = true, value = "data")
        @JsonPropertyDescription("UTF-8 string data whose signature should be verified.")
        String data,

        @JsonProperty(required = true, value = "publicKey")
        @JsonPropertyDescription("Public key in X.509 DER Base64 or PEM format.")
        String publicKey,

        @JsonProperty(required = true, value = "signature")
        @JsonPropertyDescription("Base64 signature value produced by signData.")
        String signature,

        @JsonProperty(required = true, value = "algorithm")
        @JsonPropertyDescription("Signature algorithm, for example SHA256withRSA.")
        String algorithm
) {}
