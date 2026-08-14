package sh.vork.hub.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubRepositoryRegistryServiceTest {

    private Path tempDir;

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null) {
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void resolveRepositoriesReturnsDefaultsWhenPropertyMissing() {
        MockEnvironment env = new MockEnvironment();
        HubRepositoryRegistryService service = new HubRepositoryRegistryService(env, new FakeHttpClient(200, 200));

        List<HubRepositoryDefinition> repositories = service.resolveRepositories();

        assertEquals(2, repositories.size());
        assertEquals("Production", repositories.get(0).name());
        assertEquals("Staging", repositories.get(1).name());
    }

    @Test
    void resolveRepositoriesAcceptsValidFileRepository() throws Exception {
        tempDir = Files.createTempDirectory("hub-repo-");

        MockEnvironment env = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Local=file://" + tempDir.toAbsolutePath());

        HubRepositoryRegistryService service = new HubRepositoryRegistryService(env, new FakeHttpClient(200, 200));
        List<HubRepositoryDefinition> repositories = service.resolveRepositories();

        assertEquals(3, repositories.size());
        HubRepositoryDefinition local = repositories.get(2);
        assertEquals("Local", local.name());
        assertTrue(local.available());
        assertTrue(local.baseUrl().toString().startsWith("file://"));
    }

    @Test
    void resolveRepositoriesSkipsInvalidEntriesWhenFailFastDisabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Broken=not-a-url");

        HubRepositoryRegistryService service = new HubRepositoryRegistryService(env, new FakeHttpClient(200, 200));
        List<HubRepositoryDefinition> repositories = service.resolveRepositories();

        assertEquals(2, repositories.size());
    }

    @Test
    void resolveRepositoriesThrowsForInvalidEntryWhenFailFastEnabled() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Broken=not-a-url")
            .withProperty("vork.additionalRepositoriesFailFast", "true");

        HubRepositoryRegistryService service = new HubRepositoryRegistryService(env, new FakeHttpClient(200, 200));

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::resolveRepositories);
        assertTrue(ex.getMessage().contains("Invalid repository entry"));
    }

    @Test
    void resolveRepositoriesRejectsDuplicateNames() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Repo=https://example.com/a, repo=https://example.com/b")
            .withProperty("vork.additionalRepositoriesFailFast", "true");

        HubRepositoryRegistryService service = new HubRepositoryRegistryService(env, new FakeHttpClient(200, 200));

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::resolveRepositories);
        assertTrue(ex.getMessage().contains("Duplicate repository name"));
    }

    @Test
    void resolveRepositoriesPreservesPathAndMarksHttpUnavailableWhenProbeFails() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Examples=https://example.com/repositories/examples");

        HubRepositoryRegistryService service = new HubRepositoryRegistryService(env, new FakeHttpClient(503, 503));
        List<HubRepositoryDefinition> repositories = service.resolveRepositories();

        assertEquals(3, repositories.size());
        HubRepositoryDefinition examples = repositories.get(2);
        assertEquals(URI.create("https://example.com/repositories/examples"), examples.baseUrl());
        assertFalse(examples.available());
    }

    @Test
    void resolveRepositoriesFallsBackToGetWhenHeadNotAllowed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Examples=https://example.com/repositories/examples");

        HubRepositoryRegistryService service = new HubRepositoryRegistryService(env, new FakeHttpClient(405, 200));
        List<HubRepositoryDefinition> repositories = service.resolveRepositories();

        HubRepositoryDefinition examples = repositories.get(2);
        assertNotNull(examples);
        assertTrue(examples.available());
    }

    private static final class FakeHttpClient extends HttpClient {

        private final int headStatus;
        private final int getStatus;

        private FakeHttpClient(int headStatus, int getStatus) {
            this.headStatus = headStatus;
            this.getStatus = getStatus;
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<java.time.Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            int status = "HEAD".equalsIgnoreCase(request.method()) ? headStatus : getStatus;
            return new FakeHttpResponse<>(status, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("Not used in tests");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("Not used in tests");
        }
    }

    private record FakeHttpResponse<T>(int statusCode, HttpRequest request) implements HttpResponse<T> {

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }

        @Override
        public T body() {
            return null;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
