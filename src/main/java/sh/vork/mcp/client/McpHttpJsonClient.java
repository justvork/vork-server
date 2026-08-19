package sh.vork.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sh.vork.mcp.client.dto.McpDiscoverResult;
import sh.vork.mcp.client.dto.McpDiscoveredPrompt;
import sh.vork.mcp.client.dto.McpDiscoveredResource;
import sh.vork.mcp.client.dto.McpDiscoveredTool;
import sh.vork.mcp.client.dto.McpDiscoveredToolParameter;
import sh.vork.mcp.model.McpTransportMode;

import java.io.IOException;
import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class McpHttpJsonClient {

    private static final Logger log = LoggerFactory.getLogger(McpHttpJsonClient.class);

    private final ObjectMapper objectMapper;
    private final Map<String, ResolvedRoute> resolvedRoutes = new ConcurrentHashMap<>();

    public McpHttpJsonClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public McpDiscoverResult discover(McpClientConfig config) {
        log.debug("ENTER discover: baseUrl={}, mode={}", config.baseUrl(), config.transportMode());

        log.info("[MCP-SDK] Attempting discover via SDK transport layer: baseUrl={}, mode={}",
                config.baseUrl(), config.transportMode());
        McpDiscoverResult result = executeAgainstCandidates(config, "discover", client -> {
            client.initialize();
            McpSchema.ServerCapabilities caps = client.getServerCapabilities();
            
            // Only call list methods for capabilities the server advertises
            List<McpDiscoveredTool> tools = caps.tools() != null 
                    ? parseTools(client.listTools()) 
                    : List.of();
            List<McpDiscoveredResource> resources = caps.resources() != null 
                    ? parseResources(client.listResources()) 
                    : List.of();
            List<McpDiscoveredPrompt> prompts = caps.prompts() != null 
                    ? parsePrompts(client.listPrompts()) 
                    : List.of();
            
            log.debug("Server capabilities: tools={}, resources={}, prompts={}", 
                    caps.tools() != null, caps.resources() != null, caps.prompts() != null);
            return new McpDiscoverResult(tools, resources, prompts);
        });
        log.info("[MCP-SDK] ✓ Discover succeeded via SDK transport layer");

        log.debug("EXIT discover: tools={}, resources={}, prompts={}",
                result.tools().size(), result.resources().size(), result.prompts().size());
        return result;
    }

    public McpInvocationResult invokeTool(McpClientConfig config, String toolName, Map<String, Object> arguments) {
        log.debug("ENTER invokeTool: baseUrl={}, toolName={}", config.baseUrl(), toolName);
        
        log.info("[MCP-SDK] Attempting tools/call via SDK transport layer: tool={}", toolName);
        McpSchema.CallToolResult result = executeAgainstCandidates(config, "tools/call", client -> {
            client.initialize();
            return client.callTool(new McpSchema.CallToolRequest(
                    toolName,
                    arguments == null ? Map.of() : arguments));
        });
        log.info("[MCP-SDK] ✓ tools/call succeeded via SDK transport layer");

        try {
            String body = objectMapper.writeValueAsString(result);
            log.debug("EXIT invokeTool: bodyLength={}", body.length());
            return new McpInvocationResult(200, body);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize MCP tool result", ex);
        }
    }

    private <T> T executeAgainstCandidates(McpClientConfig config,
                                           String operation,
                                           java.util.function.Function<McpSyncClient, T> action) {
        List<URI> rpcUris = resolveRpcUris(config.baseUrl(), config.transportMode());
        String routeKey = routeKey(config.baseUrl(), config.transportMode());
        IllegalStateException lastFailure = null;
        HttpClient probeClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        assertBaseReachable(config.baseUrl(), probeClient, operation);

        // Fast path: retry the last known-good route first.
        ResolvedRoute cached = resolvedRoutes.get(routeKey);
        if (cached != null) {
            AttemptResult<T> cachedAttempt = tryResolvedRoute(config, operation, action, cached);
            if (cachedAttempt.success()) {
                return cachedAttempt.value();
            }
            lastFailure = cachedAttempt.error();
            resolvedRoutes.remove(routeKey);
            log.debug("Cached MCP route failed and was evicted [operation={}, key={}, transport={}, uri={}]",
                    operation, routeKey, cached.transport(), cached.rpcUri());
        }

        for (URI rpcUri : rpcUris) {
            log.debug("Trying selected MCP transport for candidate [operation={}, uri={}, mode={}]",
                    operation, rpcUri, config.transportMode());

            AttemptResult<T> attempt = tryCandidateWithSelectedTransport(
                    config,
                    operation,
                    action,
                    probeClient,
                    rpcUri,
                    routeKey
            );
            if (attempt.success()) {
                return attempt.value();
            }
            if (attempt.error() != null) {
                lastFailure = attempt.error();
            }
        }

        if (lastFailure != null) {
            throw new IllegalStateException("MCP request failed for selected transport mode "
                + config.transportMode() + ". Tried: " + rpcUris + ".", lastFailure);
        }

        throw new IllegalStateException("MCP request failed for operation " + operation + ": no endpoint candidates available");
    }

    private void assertBaseReachable(String baseUrl, HttpClient probeClient, String operation) {
        URI baseUri = URI.create(baseUrl == null ? "" : baseUrl.trim());
        URI healthUri = joinUri(baseUri, "/");
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build();
        try {
            probeClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException ioEx) {
            if (isConnectFailure(ioEx)) {
                throw new IllegalStateException("MCP endpoint is unreachable for operation " + operation
                        + " at base URL " + baseUrl
                        + ". Verify the MCP server is running and listening on this host/port.", ioEx);
            }
        } catch (InterruptedException interruptedEx) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP reachability probe interrupted for base URL " + baseUrl
                    + " during operation " + operation + ".", interruptedEx);
        }
    }

    private static boolean isConnectFailure(IOException ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof ConnectException) {
            return true;
        }
        String message = ex.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("failed to connect") || lower.contains("connection refused")) {
                return true;
            }
        }
        Throwable cause = ex.getCause();
        return cause instanceof ConnectException;
    }

    private <T> AttemptResult<T> tryCandidateWithSelectedTransport(McpClientConfig config,
                                                                    String operation,
                                                                    java.util.function.Function<McpSyncClient, T> action,
                                                                    HttpClient probeClient,
                                                                    URI rpcUri,
                                                                    String routeKey) {
        if (config.transportMode() == McpTransportMode.SSE) {
            return trySse(config, operation, action, probeClient, rpcUri, null, routeKey);
        }

        if (config.transportMode() == McpTransportMode.STREAMABLE_HTTP) {
            return tryStreamable(config, operation, action, rpcUri, null, routeKey);
        }

        return AttemptResult.failure(new IllegalStateException(
                "Unsupported MCP transport mode: " + config.transportMode()));
    }

    private <T> AttemptResult<T> tryStreamable(McpClientConfig config,
                                               String operation,
                                               java.util.function.Function<McpSyncClient, T> action,
                                               URI rpcUri,
                                               IllegalStateException lastFailure,
                                               String routeKey) {
        for (String streamableEndpoint : resolveStreamableEndpoints(rpcUri)) {
            for (StreamableTransportRequest request : resolveStreamableTransportRequests(rpcUri, streamableEndpoint)) {
                try (McpSyncClient streamableClient = buildStreamableClient(
                        config,
                        request.baseUri(),
                        request.endpoint(),
                        request.strategyLabel())) {
                    log.info("[MCP-SDK] Trying SDK HttpClientStreamableHttpTransport [operation={}, baseUri={}, endpoint={}, strategy={}]",
                            operation, request.baseUri(), request.endpoint(), request.strategyLabel());
                    T value = action.apply(streamableClient);
                    log.info("[MCP-SDK] ✓ SDK HttpClientStreamableHttpTransport succeeded [operation={}, endpoint={}]",
                            operation, request.endpoint());
                    resolvedRoutes.put(routeKey, new ResolvedRoute(rpcUri, ResolvedTransport.STREAMABLE_HTTP, null, streamableEndpoint));
                    return AttemptResult.success(value);
                } catch (RuntimeException ex) {
                    URI fullUri = joinUri(rpcUri, streamableEndpoint);
                    IllegalStateException wrapped = toClientFailure(operation, fullUri, ex);
                    lastFailure = wrapped;
                    log.warn("[MCP-SDK] ✗ SDK HttpClientStreamableHttpTransport failed [operation={}, baseUri={}, endpoint={}, strategy={}]",
                            operation,
                            request.baseUri(),
                            request.endpoint(),
                            request.strategyLabel());
                    log.warn("[MCP-SDK]   Root cause: {} - {}", ex.getClass().getSimpleName(), safeMessage(ex.getMessage()));
                    if (ex.getCause() != null) {
                        log.warn("[MCP-SDK]   Caused by: {} - {}", ex.getCause().getClass().getSimpleName(), safeMessage(ex.getCause().getMessage()));
                    }
                }
            }
        }
        return AttemptResult.failure(lastFailure);
    }

    private <T> AttemptResult<T> trySse(McpClientConfig config,
                                        String operation,
                                        java.util.function.Function<McpSyncClient, T> action,
                                        HttpClient probeClient,
                                        URI rpcUri,
                                        IllegalStateException lastFailure,
                                        String routeKey) {
        for (String sseEndpoint : resolveSseEndpoints(rpcUri)) {
            ProbeResult probeResult = preflightProbe(probeClient, rpcUri, sseEndpoint, config.authorizationHeaderValue());
            if (probeResult == ProbeResult.NOT_FOUND_OR_HTML) {
                log.debug("Skipping non-MCP SSE endpoint candidate before SDK init [operation={}, uri={}, sseEndpoint={}]",
                        operation, rpcUri, sseEndpoint);
                continue;
            }
            try (McpSyncClient client = buildClient(config, rpcUri, sseEndpoint)) {
                log.debug("Attempting MCP transport strategy [operation={}, uri={}, transport=sse, sseEndpoint={}]",
                        operation, rpcUri, sseEndpoint);
                T value = action.apply(client);
                resolvedRoutes.put(routeKey, new ResolvedRoute(rpcUri, ResolvedTransport.SSE, sseEndpoint, null));
                return AttemptResult.success(value);
            } catch (RuntimeException ex) {
                // For error reporting: origin + sseEndpoint
                String fullUri = normalizeOriginUri(rpcUri) + sseEndpoint;
                IllegalStateException wrapped = toClientFailure(operation, URI.create(fullUri), ex);
                lastFailure = wrapped;
                log.debug("MCP sse attempt failed [operation={}, uri={}, sseEndpoint={}]: {}",
                        operation,
                        rpcUri,
                        sseEndpoint,
                        safeMessage(wrapped.getMessage()));
            }
        }
        return AttemptResult.failure(lastFailure);
    }

    private <T> AttemptResult<T> tryResolvedRoute(McpClientConfig config,
                                                  String operation,
                                                  java.util.function.Function<McpSyncClient, T> action,
                                                  ResolvedRoute route) {
        try {
            if (route.transport() == ResolvedTransport.STREAMABLE_HTTP) {
                String endpoint = route.streamableEndpoint() == null ? "/" : route.streamableEndpoint();
                RuntimeException lastError = null;
                for (StreamableTransportRequest request : resolveStreamableTransportRequests(route.rpcUri(), endpoint)) {
                    try (McpSyncClient client = buildStreamableClient(
                            config,
                            request.baseUri(),
                            request.endpoint(),
                            request.strategyLabel())) {
                        log.debug("Attempting cached MCP route [operation={}, transport=streamable-http, uri={}, endpoint={}, strategy={}]",
                                operation, route.rpcUri(), endpoint, request.strategyLabel());
                        return AttemptResult.success(action.apply(client));
                    } catch (RuntimeException ex) {
                        lastError = ex;
                    }
                }
                if (lastError != null) {
                    throw lastError;
                }
            }

            if (route.transport() == ResolvedTransport.SSE) {
                String endpoint = route.sseEndpoint() == null ? "/sse" : route.sseEndpoint();
                try (McpSyncClient client = buildClient(config, route.rpcUri(), endpoint)) {
                    log.debug("Attempting cached MCP route [operation={}, transport=sse, uri={}, sseEndpoint={}]",
                            operation, route.rpcUri(), endpoint);
                    return AttemptResult.success(action.apply(client));
                }
            }

            return AttemptResult.failure(new IllegalStateException("Unknown cached MCP transport route"));
        } catch (RuntimeException ex) {
            URI fullUri = route.transport() == ResolvedTransport.SSE
                    ? joinUri(route.rpcUri(), route.sseEndpoint() == null ? "/sse" : route.sseEndpoint())
                    : joinUri(route.rpcUri(), route.streamableEndpoint() == null ? "/" : route.streamableEndpoint());
            return AttemptResult.failure(toClientFailure(operation, fullUri, ex));
        }
    }

    private ProbeResult preflightProbe(HttpClient probeClient,
                                       URI rpcUri,
                                       String sseEndpoint,
                                       String authorizationHeaderValue) {
        try {
            // For SSE: origin + sseEndpoint (don't double-concatenate paths)
            String baseUri = normalizeOriginUri(rpcUri);
            String endpoint = sseEndpoint.startsWith("/") ? sseEndpoint : "/" + sseEndpoint;
            URI sseUri = URI.create(baseUri + endpoint);
            HttpRequest.Builder builder = HttpRequest.newBuilder(sseUri)
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json, text/event-stream");
            if (authorizationHeaderValue != null && !authorizationHeaderValue.isBlank()) {
                builder.header("Authorization", authorizationHeaderValue);
            }

            HttpResponse<String> response = probeClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            String body = response.body() == null ? "" : response.body().trim();
            boolean looksLikeHtml = contentType.contains("text/html")
                    || body.startsWith("<!DOCTYPE html")
                    || body.startsWith("<html");

            // Some MCP servers expose transport only for POST/upgrade flows and can return
            // 404 to plain GET probes. Treat only clear HTML responses as non-MCP.
            if (looksLikeHtml) {
                return ProbeResult.NOT_FOUND_OR_HTML;
            }
            return ProbeResult.OK_OR_UNKNOWN;
        } catch (Exception ex) {
            return ProbeResult.OK_OR_UNKNOWN;
        }
    }

    private McpSyncClient buildClient(McpClientConfig config, URI rpcUri, String sseEndpoint) {
        // For SSE: base is just origin (scheme://authority), path goes in sseEndpoint
        String normalizedBaseUri = normalizeOriginUri(rpcUri);
        if (log.isDebugEnabled()) {
            log.debug("MCP SDK client request [endpoint={}, sseEndpoint={}, authScheme={}]",
                    normalizedBaseUri,
                    sseEndpoint,
                    authorizationScheme(config.authorizationHeaderValue()));
        }

        HttpClientSseClientTransport.Builder transportBuilder = HttpClientSseClientTransport.builder(normalizedBaseUri)
                .sseEndpoint(sseEndpoint);

        // Add Authorization header if configured (matching SDK 2.0.0 pattern)
        if (config.authorizationHeaderValue() != null && !config.authorizationHeaderValue().isBlank()) {
            transportBuilder.requestBuilder(
                    HttpRequest.newBuilder().header("Authorization", config.authorizationHeaderValue()));
        }

        HttpClientSseClientTransport transport = transportBuilder.build();

        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .initializationTimeout(Duration.ofSeconds(15))
                .clientInfo(new McpSchema.Implementation("vork", "1.0"))
                .build();
    }

    private McpSyncClient buildStreamableClient(McpClientConfig config,
                                                String normalizedBaseUri,
                                                String normalizedEndpoint,
                                                String strategyLabel) {
        log.debug("[MCP-SDK] Building HttpClientStreamableHttpTransport: baseUri={}, endpoint={}, strategy={}",
                normalizedBaseUri, normalizedEndpoint, strategyLabel);

        HttpClientStreamableHttpTransport.Builder transportBuilder = HttpClientStreamableHttpTransport.builder(normalizedBaseUri)
                .endpoint(normalizedEndpoint);

        // Add Authorization header if configured (matching working pattern)
        if (config.authorizationHeaderValue() != null && !config.authorizationHeaderValue().isBlank()) {
            transportBuilder.requestBuilder(
                    HttpRequest.newBuilder().header("Authorization", config.authorizationHeaderValue()));
        }

        HttpClientStreamableHttpTransport transport = transportBuilder.build();

        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .build();
    }

    private static List<StreamableTransportRequest> resolveStreamableTransportRequests(URI rpcUri, String endpoint) {
        // Simple and direct: origin + path from URI (like working code pattern)
        String baseUri = normalizeOriginUri(rpcUri);
        String endpointPath = rpcUri.getPath();
        if (endpointPath == null || endpointPath.isBlank()) {
            endpointPath = "/";
        }
        return List.of(new StreamableTransportRequest(baseUri, endpointPath, "direct"));
    }

    private static List<String> resolveStreamableEndpoints(URI rpcUri) {
        // Just use the path from the URI directly - no guessing
        return List.of("");
    }

    private static List<String> resolveSseEndpoints(URI rpcUri) {
        // Extract the path from the URI - SDK requires non-empty sseEndpoint
        String path = rpcUri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return List.of("/sse");  // Default SSE endpoint
        }
        return List.of(path);
    }

    private static String routeKey(String baseUrl, McpTransportMode mode) {
        if (baseUrl == null) {
            return "|" + (mode == null ? "" : mode.name());
        }
        return baseUrl.trim() + "|" + (mode == null ? "" : mode.name());
    }

    private static URI joinUri(URI baseUri, String endpointPath) {
        String base = normalizeBaseUri(baseUri);
        String endpoint = endpointPath == null || endpointPath.isBlank() ? "/" : endpointPath;
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        return URI.create(base + endpoint);
    }

    private static String normalizeBaseUri(URI uri) {
        String base = uri.toString();
        while (base.endsWith("/") && !base.matches("^https?://[^/]+/$")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String normalizeOriginUri(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private IllegalStateException toClientFailure(String operation, URI rpcUri, RuntimeException ex) {
        if (ex instanceof McpError mcpError) {
            var jsonRpcError = mcpError.getJsonRpcError();
            String detail = jsonRpcError == null ? ex.getMessage() : String.valueOf(jsonRpcError.message());
            return new IllegalStateException("MCP request failed for operation " + operation
                    + " at endpoint " + rpcUri + ": " + safeMessage(detail), ex);
        }

        String message = ex.getMessage();
        if (message != null && message.contains("status 404")) {
            return new IllegalStateException("MCP request failed with status 404 for endpoint " + rpcUri
                    + ": " + safeMessage(message), ex);
        }
        if (message != null && (message.toLowerCase().contains("timed out") || message.toLowerCase().contains("timeout"))) {
            return new IllegalStateException("MCP request timed out for operation " + operation
                    + " at endpoint " + rpcUri
                    + ". Check endpoint reachability, auth gateway behavior, and MCP transport compatibility.", ex);
        }
        if (message != null && (message.toLowerCase().contains("connection") || message.toLowerCase().contains("connect"))) {
            return new IllegalStateException("MCP connection failed for operation " + operation
                    + " at endpoint " + rpcUri
                    + ". Verify host/port/network path.", ex);
        }
        if (message != null && message.contains("Client failed to initialize by explicit API call")) {
            return new IllegalStateException("MCP initialization failed for operation " + operation
                + " at endpoint " + rpcUri
                + ". The target may not expose a compatible MCP transport endpoint at this path "
                + "for the attempted strategy (streamable-http or SSE).", ex);
        }

        return new IllegalStateException("MCP request failed for operation " + operation
                + " at endpoint " + rpcUri + ": " + safeMessage(message), ex);
    }

    private static String authorizationScheme(String authorizationHeaderValue) {
        if (authorizationHeaderValue == null || authorizationHeaderValue.isBlank()) {
            return "none";
        }
        String trimmed = authorizationHeaderValue.trim();
        int space = trimmed.indexOf(' ');
        if (space <= 0) {
            return "raw";
        }
        return trimmed.substring(0, space);
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "<no detail>";
        }
        return message.length() > 260 ? message.substring(0, 260) + "..." : message;
    }

    private static List<URI> resolveRpcUris(String baseUrl, McpTransportMode mode) {
        URI base = URI.create(baseUrl == null ? "" : baseUrl.trim());
        String path = base.getPath() == null ? "" : base.getPath().trim();

        // If user provides a path, use it exactly.
        // If user provides only host:port (no path), default based on transport mode.
        String finalPath;
        if (path.isEmpty() || "/".equals(path)) {
            // No explicit path - default based on transport
            finalPath = switch (mode) {
                case STREAMABLE_HTTP -> "/mcp";
                case SSE -> "/sse";
                case null -> "/mcp";  // default to streamable if mode not specified
            };
        } else {
            finalPath = path;
        }
        return List.of(URI.create(base.getScheme() + "://" + base.getAuthority() + finalPath));
    }

    @SuppressWarnings("unchecked")
    private List<McpDiscoveredTool> parseTools(McpSchema.ListToolsResult listToolsResult) {
        List<McpDiscoveredTool> result = new ArrayList<>();
        if (listToolsResult == null || listToolsResult.tools() == null) {
            return result;
        }
        for (McpSchema.Tool tool : listToolsResult.tools()) {
            String name = tool.name() == null ? "" : tool.name();
            String description = tool.description() == null ? "" : tool.description();
            
            // SDK 2.0.0: inputSchema() returns Map<String, Object> not JsonSchema
            Map<String, Object> inputSchema = tool.inputSchema();
            String inputSchemaJson;
            try {
                inputSchemaJson = objectMapper.writeValueAsString(inputSchema);
            } catch (IOException ex) {
                inputSchemaJson = "{}";
            }

            List<McpDiscoveredToolParameter> parameters = new ArrayList<>();
            Map<String, Object> properties = Map.of();
            List<String> required = List.of();
            
            if (inputSchema != null) {
                Object propsObj = inputSchema.get("properties");
                if (propsObj instanceof Map<?, ?>) {
                    properties = (Map<String, Object>) propsObj;
                }
                Object reqObj = inputSchema.get("required");
                if (reqObj instanceof List<?>) {
                    required = ((List<?>) reqObj).stream()
                            .filter(o -> o instanceof String)
                            .map(o -> (String) o)
                            .toList();
                }
            }

            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    String paramName = entry.getKey();
                    JsonNode paramNode = objectMapper.valueToTree(entry.getValue());
                    boolean isRequired = false;
                    for (String req : required) {
                        if (paramName.equals(req)) {
                            isRequired = true;
                            break;
                        }
                    }
                    parameters.add(new McpDiscoveredToolParameter(
                            paramName,
                            paramNode.path("type").asText("string"),
                            isRequired,
                            paramNode.path("description").asText(""),
                            paramNode.path("default").asText("")));
            }

            result.add(new McpDiscoveredTool(name, name, description, inputSchemaJson, parameters));
        }
        return result;
    }

    private List<McpDiscoveredResource> parseResources(McpSchema.ListResourcesResult listResourcesResult) {
        List<McpDiscoveredResource> result = new ArrayList<>();
        if (listResourcesResult == null || listResourcesResult.resources() == null) {
            return result;
        }
        for (McpSchema.Resource resource : listResourcesResult.resources()) {
            String uri = resource.uri() == null ? "" : resource.uri();
            result.add(new McpDiscoveredResource(
                    uri.isBlank() ? (resource.name() == null ? "" : resource.name()) : uri,
                    resource.name() == null ? "" : resource.name(),
                    resource.description() == null ? "" : resource.description(),
                    uri,
                    "{}"));
        }
        return result;
    }

    private List<McpDiscoveredPrompt> parsePrompts(McpSchema.ListPromptsResult listPromptsResult) {
        List<McpDiscoveredPrompt> result = new ArrayList<>();
        if (listPromptsResult == null || listPromptsResult.prompts() == null) {
            return result;
        }
        for (McpSchema.Prompt prompt : listPromptsResult.prompts()) {
            String argSchemaJson = "{}";
            try {
                argSchemaJson = objectMapper.writeValueAsString(prompt.arguments());
            } catch (IOException ignored) {
            }

            result.add(new McpDiscoveredPrompt(
                    prompt.name() == null ? "" : prompt.name(),
                    prompt.name() == null ? "" : prompt.name(),
                    prompt.description() == null ? "" : prompt.description(),
                    argSchemaJson));
        }
        return result;
    }

    private enum ProbeResult {
        OK_OR_UNKNOWN,
        NOT_FOUND_OR_HTML
    }

    private enum ResolvedTransport {
        STREAMABLE_HTTP,
        SSE
    }

    private record ResolvedRoute(URI rpcUri,
                                 ResolvedTransport transport,
                                 String sseEndpoint,
                                 String streamableEndpoint) {
    }

    private record AttemptResult<T>(boolean success, T value, IllegalStateException error) {
        private static <T> AttemptResult<T> success(T value) {
            return new AttemptResult<>(true, value, null);
        }

        private static <T> AttemptResult<T> failure(IllegalStateException error) {
            return new AttemptResult<>(false, null, error);
        }
    }

    private record StreamableTransportRequest(String baseUri, String endpoint, String strategyLabel) {
    }
}
