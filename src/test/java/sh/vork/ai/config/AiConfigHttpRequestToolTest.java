package sh.vork.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.oauth.OAuthClientService;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

class AiConfigHttpRequestToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void httpRequest_binaryMode_savesToSessionFileSystem() throws Exception {
        HttpServer server = startServer("/bin", "ZIPDATA".getBytes(StandardCharsets.UTF_8), "application/zip");
        int port = server.getAddress().getPort();

        try {
            JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
            TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
            OAuthClientService oauthClientService = mock(OAuthClientService.class);
            SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

            when(sessionFileSystem.write(eq(FileArea.SESSION), eq("session-http-1"), eq("tools/node.zip"), any(), eq(7L)))
                    .thenReturn(new FileDescriptor(
                            FileArea.SESSION,
                            "session-http-1",
                            "tools/node.zip",
                            7,
                            "/api/session-files/download?area=SESSION&sessionUuid=session-http-1&path=tools%2Fnode.zip"));

            AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
            ToolCallback callback = config.httpRequest(oauthClientService, sessionFileSystem);

            MDC.put("sessionUuid", "session-http-1");
            String input = objectMapper.writeValueAsString(Map.of(
                    "method", "GET",
                    "url", "http://127.0.0.1:" + port + "/bin",
                    "responseMode", "BINARY",
                    "area", "SESSION",
                    "saveToPath", "tools/node.zip"));

            String output = callback.call(input);
            Map<String, Object> map = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {
            });

            assertEquals(200, ((Number) map.get("statusCode")).intValue());
            assertEquals(Boolean.TRUE, map.get("saved"));
            assertEquals("tools/node.zip", map.get("path"));
            verify(sessionFileSystem).write(eq(FileArea.SESSION), eq("session-http-1"), eq("tools/node.zip"), any(), eq(7L));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpRequest_binaryMode_requiresSessionContextForSessionArea() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        OAuthClientService oauthClientService = mock(OAuthClientService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback callback = config.httpRequest(oauthClientService, sessionFileSystem);

        String input = objectMapper.writeValueAsString(Map.of(
                "method", "GET",
                "url", "https://example.com/archive.zip",
                "responseMode", "BINARY",
                "area", "SESSION",
                "saveToPath", "tools/archive.zip"));

        String output = callback.call(input);
        assertTrue(output.contains("Session context is required"));
    }

    private static HttpServer startServer(String path, byte[] payload, String contentType) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        return server;
    }
}
