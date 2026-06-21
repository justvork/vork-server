package sh.vork.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayHttpClientTest {

    @Test
    void uploadSendsRelayTokenHeaderWhenConfigured() throws Exception {
        AtomicReference<String> seenToken = new AtomicReference<>(null);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/v1/relay", exchange -> handleUpload(exchange, seenToken));
            server.start();

            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:" + port;
            String sessionId = UUID.randomUUID().toString();

            RelayHttpClient client = new RelayHttpClient(new ObjectMapper(), "shared-secret");
            client.upload(baseUrl, sessionId, "cipher", "nonce", "tag", 15);

            assertEquals("shared-secret", seenToken.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void uploadOmitsRelayTokenHeaderWhenNotConfigured() throws Exception {
        AtomicReference<String> seenToken = new AtomicReference<>("present");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/api/v1/relay", exchange -> handleUpload(exchange, seenToken));
            server.start();

            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:" + port;
            String sessionId = UUID.randomUUID().toString();

            RelayHttpClient client = new RelayHttpClient(new ObjectMapper(), "");
            client.upload(baseUrl, sessionId, "cipher", "nonce", "tag", 15);

            assertEquals(null, seenToken.get());
        } finally {
            server.stop(0);
        }
    }

    private static void handleUpload(HttpExchange exchange, AtomicReference<String> seenToken)
            throws IOException {
        seenToken.set(exchange.getRequestHeaders().getFirst("X-Relay-Token"));
        exchange.sendResponseHeaders(201, -1);
        exchange.close();
    }
}
