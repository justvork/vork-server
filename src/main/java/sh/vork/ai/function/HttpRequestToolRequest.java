package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Input for the {@code httpRequest} tool.
 *
 * <ul>
 *   <li>{@code method}  — HTTP method (GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS).
 *       Defaults to GET when omitted.</li>
 *   <li>{@code url}     — Fully-qualified http/https URL, including any query-string
 *       parameters for GET requests.</li>
 *   <li>{@code headers} — Optional map of request headers (e.g. Content-Type,
 *       Authorization).</li>
 *   <li>{@code body}    — Optional request body as a string (JSON, form-encoded, plain
 *       text, etc.). Ignored for GET and HEAD.</li>
 *   <li>{@code responseMode} — Optional response handling mode. TEXT (default) or BINARY.</li>
 *   <li>{@code area} — Optional target storage area when responseMode=BINARY. SESSION (default) or SHARED.</li>
 *   <li>{@code saveToPath} — Optional destination file path when responseMode=BINARY.</li>
 * </ul>
 */
public record HttpRequestToolRequest(

        @JsonProperty(value = "method")
        @JsonPropertyDescription("HTTP method: GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS. Defaults to GET.")
        String method,

        @JsonProperty(required = true, value = "url")
        @JsonPropertyDescription("Fully-qualified http or https URL. Include query parameters in the URL for GET requests.")
        String url,

        @JsonProperty(value = "headers")
        @JsonPropertyDescription("Optional map of HTTP request headers, e.g. {\"Content-Type\": \"application/json\", \"Authorization\": \"Bearer token\"}.")
        Map<String, String> headers,

        @JsonProperty(value = "body")
        @JsonPropertyDescription("Optional request body as a string. For JSON APIs supply a JSON string. Ignored for GET and HEAD requests.")
        String body,

        @JsonProperty(value = "responseMode")
        @JsonPropertyDescription("Response mode: TEXT (default) or BINARY.")
        String responseMode,

        @JsonProperty(value = "area")
        @JsonPropertyDescription("When responseMode=BINARY, target file area: SESSION (default) or SHARED.")
        String area,

        @JsonProperty(value = "saveToPath")
        @JsonPropertyDescription("When responseMode=BINARY, destination file path to write response bytes.")
        String saveToPath

) {

        private static final ObjectMapper HEADER_OBJECT_MAPPER = new ObjectMapper();

        @JsonCreator
        public static HttpRequestToolRequest create(
                        @JsonProperty("method") String method,
                        @JsonProperty("url") String url,
                        @JsonProperty("headers") Object headers,
                        @JsonProperty("body") String body,
                        @JsonProperty("responseMode") String responseMode,
                        @JsonProperty("area") String area,
                        @JsonProperty("saveToPath") String saveToPath) {
                return new HttpRequestToolRequest(
                        method,
                        url,
                        normalizeHeaders(headers),
                        body,
                        responseMode,
                        area,
                        saveToPath);
        }

        private static Map<String, String> normalizeHeaders(Object headers) {
                if (headers == null) {
                        return null;
                }

                if (headers instanceof Map<?, ?> rawMap) {
                        Map<String, String> normalized = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                                if (entry.getKey() == null || entry.getValue() == null) {
                                        continue;
                                }
                                normalized.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                        }
                        return normalized;
                }

                if (headers instanceof String rawString) {
                        String text = rawString.trim();
                        if (text.isEmpty()) {
                                return Map.of();
                        }

                        // Handle model outputs that send a JSON object as a string.
                        if (text.startsWith("{") && text.endsWith("}")) {
                                try {
                                        return HEADER_OBJECT_MAPPER.readValue(text, new TypeReference<Map<String, String>>() {
                                        });
                                } catch (Exception ignored) {
                                        // Fall through to plain header parsing.
                                }
                        }

                        // Handle simple "Header-Name: value" form.
                        int colon = text.indexOf(':');
                        if (colon > 0 && colon < text.length() - 1) {
                                String key = text.substring(0, colon).trim();
                                String value = text.substring(colon + 1).trim();
                                if (!key.isEmpty() && !value.isEmpty()) {
                                        Map<String, String> single = new LinkedHashMap<>();
                                        single.put(key, value);
                                        return single;
                                }
                        }
                }

                throw new IllegalArgumentException("headers must be an object map, a JSON object string, or a single 'Header: value' string");
        }
}
