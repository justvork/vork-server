package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ConfigureEncryptionRequest(
        @JsonProperty(value = "type")
        @JsonPropertyDescription("Encryption mode for this session: RSA or SOFTWARE.")
        String type,

        @JsonProperty(value = "filePath")
        @JsonPropertyDescription("Session-sandbox file path. RSA expects a PKCS#8 private key. SOFTWARE expects a .p12 keystore.")
        String filePath,

        @JsonProperty(value = "keystoreAlias")
        @JsonPropertyDescription("Optional SOFTWARE keystore alias. If omitted, provider default is used.")
        String keystoreAlias,

        @JsonProperty(value = "keystorePassword")
        @JsonPropertyDescription("Optional SOFTWARE keystore password. If omitted, provider default is used.")
        String keystorePassword,

        @JsonProperty(value = "clear")
        @JsonPropertyDescription("Set true to clear session encryption override and revert to system default encryption.")
        Boolean clear
) {}
