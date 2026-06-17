package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

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
        String body

) {}
