package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code verifyDataByRef} tool.
 */
public record VerifyDataByRefRequest(
        @JsonProperty(required = true, value = "data")
        @JsonPropertyDescription("UTF-8 string data whose signature should be verified.")
        String data,

        @JsonProperty(required = true, value = "signature")
        @JsonPropertyDescription("Base64 signature value produced by signData.")
        String signature,

        @JsonProperty(required = true, value = "algorithm")
        @JsonPropertyDescription("Signature algorithm, for example SHA256withRSA or Ed25519.")
        String algorithm,

        @JsonProperty(required = true, value = "secretName")
        @JsonPropertyDescription("Key identifier from generatePrivateKey (plain name or {{secret.ref}}).")
        String secretName
) {}
