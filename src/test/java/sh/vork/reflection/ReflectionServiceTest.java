package sh.vork.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.security.SkillSecretSubstitutor;
import sh.vork.oauth.OAuthClientService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.security.SecureCredentialStore;

class ReflectionServiceTest {

    private ReflectionService reflectionService;
    private HttpClient httpClient;
        private SecureCredentialStore secureCredentialStore;

    @BeforeEach
    void setUp() {
        DatabaseRepository<Reflection> reflectionRepository = new MapDatabaseRepository<>(Reflection.class);
        DatabaseRepository<ReflectionGroup> groupRepository = new MapDatabaseRepository<>(ReflectionGroup.class);
                DatabaseRepository<ReflectionBinding> bindingRepository = new MapDatabaseRepository<>(ReflectionBinding.class);

        OAuthClientService oauthClientService = mock(OAuthClientService.class);
        when(oauthClientService.resolveHeaderValue(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        SkillSecretSubstitutor skillSecretSubstitutor = mock(SkillSecretSubstitutor.class);
        when(skillSecretSubstitutor.substitute(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        secureCredentialStore = mock(SecureCredentialStore.class);

        httpClient = mock(HttpClient.class);

        reflectionService = new ReflectionService(
                reflectionRepository,
                groupRepository,
                bindingRepository,
                oauthClientService,
                skillSecretSubstitutor,
                secureCredentialStore,
                new ObjectMapper(),
                httpClient);
    }

    @Test
    void createReflectionRejectsDuplicateAlphanumericId() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));

        ReflectionService.ReflectionRequest request = new ReflectionService.ReflectionRequest(
                "WeatherLookup",
                "Weather Lookup",
                "desc",
                group.uuid(),
                List.of(),
                "GET",
                "https://example.com/weather",
                Map.of(),
                Map.of(),
                "",
                "application/json");

        Reflection created = reflectionService.createReflection(request);
        assertNotNull(created);

        assertThrows(IllegalArgumentException.class, () -> reflectionService.createReflection(request));
    }

    @Test
    void executeRestReflectionReturnsMissingParametersWhenRequiredInputNotProvided() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "WeatherLookup",
                "Weather Lookup",
                "desc",
                group.uuid(),
                List.of(new ReflectionInputParameter("city", "string", "City name", true)),
                "GET",
                "https://example.com/weather",
                Map.of(),
                Map.of(),
                "",
                "application/json"));

        String result = reflectionService.executeRestReflection("WeatherLookup", Map.of(), null, "alice");

        assertTrue(result.contains("missing_parameters"));
        assertTrue(result.contains("city"));
    }

    @Test
    void createBindingCopiesMissingSecretsFromSourceBindingWhenRequested() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group",
                "desc",
                "REST",
                "",
                List.of(new sh.vork.skill.SkillSecret("API_KEY", "API key")),
                List.of()));

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        when(secureCredentialStore.getSecretForUser(
                eq("alice"),
                eq("REFLECTION_BINDING:" + group.uuid() + ":default:API_KEY")))
                .thenReturn("copied-secret-value");

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest(
                        "sandbox",
                        "",
                        Map.of(),
                        Map.of(),
                        "default"));

        verify(secureCredentialStore).saveSecretForUser(
                eq("alice"),
                eq("REFLECTION_BINDING:" + group.uuid() + ":sandbox:API_KEY"),
                eq("copied-secret-value"));
    }

    @Test
    void createBindingCopiesMissingSecretsFromSourceBindingWhenSecretNameIsLowercase() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group",
                "desc",
                "REST",
                "",
                List.of(new sh.vork.skill.SkillSecret("api_key", "API key")),
                List.of()));

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        when(secureCredentialStore.getSecretForUser(
                eq("alice"),
                eq("REFLECTION_BINDING:" + group.uuid() + ":default:API_KEY")))
                .thenReturn("copied-secret-value");

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest(
                        "sandbox",
                        "",
                        Map.of(),
                        Map.of(),
                        "default"));

        verify(secureCredentialStore).saveSecretForUser(
                eq("alice"),
                eq("REFLECTION_BINDING:" + group.uuid() + ":sandbox:API_KEY"),
                eq("copied-secret-value"));
    }

        @Test
        void executeRestReflectionReturnsErrorWhenNoBindingsConfigured() {
                ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                                "REST Group", "desc", "REST", "", List.of(), List.of()));

                reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                                "WeatherLookup",
                                "Weather Lookup",
                                "desc",
                                group.uuid(),
                                List.of(),
                                "GET",
                                "https://example.com/weather",
                                Map.of(),
                                Map.of(),
                                "",
                                "application/json"));

                String result = reflectionService.executeRestReflection("WeatherLookup", Map.of(), null, "alice");

                assertTrue(result.contains("\"status\":\"error\""));
                assertTrue(result.contains("No bindings configured"));
        }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionMergesQueryParametersFromInputs() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "getWeather",
                "Weather Lookup",
                "desc",
                group.uuid(),
                List.of(new ReflectionInputParameter("city", "string", "City", true)),
                "GET",
                "https://example.com/weather",
                Map.of("Accept", "application/json"),
                Map.of("units", "metric"),
                "",
                "application/json"));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection("getWeather", Map.of("city", "london"), null, "alice");

        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"statusCode\":200"));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        String calledUrl = requestCaptor.getValue().uri().toString();
        assertTrue(calledUrl.contains("units=metric"));
        assertTrue(calledUrl.contains("city=london"));
        assertEquals("GET", requestCaptor.getValue().method());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionResolvesRelativeUrlWithBindingBaseUrl() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "https://example.com/api", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "getWeatherRelative",
                "Weather Relative",
                "desc",
                group.uuid(),
                List.of(new ReflectionInputParameter("city", "string", "City", true)),
                "GET",
                "/weather",
                Map.of(),
                Map.of(),
                "",
                "application/json"));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection("getWeatherRelative", Map.of("city", "auckland"), null, "alice");

        assertTrue(result.contains("\"status\":\"ok\""));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        String calledUrl = requestCaptor.getValue().uri().toString();
        assertTrue(calledUrl.startsWith("https://example.com/api/weather"));
        assertTrue(calledUrl.contains("city=auckland"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionGeneratesFormEncodedBodyWhenTemplateMissing() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "postWeather",
                "Post Weather",
                "desc",
                group.uuid(),
                List.of(
                        new ReflectionInputParameter("city", "string", "City", true),
                        new ReflectionInputParameter("days", "int", "Days", false)),
                "POST",
                "https://example.com/weather",
                Map.of(),
                Map.of(),
                "",
                "application/x-www-form-urlencoded"));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("ok");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection(
                "postWeather",
                Map.of("city", "new york", "days", 3),
                null,
                "alice");

        assertTrue(result.contains("\"status\":\"ok\""));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        HttpRequest request = requestCaptor.getValue();
        assertEquals("POST", request.method());
        assertEquals("application/x-www-form-urlencoded",
                request.headers().firstValue("Content-Type").orElse(""));
    }

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void executeRestReflectionEncodesTemplateVariablesForFormContentType() throws Exception {
                ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                                "REST Group", "desc", "REST", "", List.of(), List.of()));
                reflectionService.createBinding("alice", group.uuid(),
                                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

                reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                                "postForm",
                                "Post Form",
                                "desc",
                                group.uuid(),
                                List.of(new ReflectionInputParameter("city", "string", "City", true)),
                                "POST",
                                "https://example.com/form",
                                Map.of(),
                                Map.of(),
                                "city={{city}}",
                                "application/x-www-form-urlencoded"));

                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn("ok");
                when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
                when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

                reflectionService.executeRestReflection("postForm", Map.of("city", "new york"), null, "alice");

                var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
                org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

                String requestBody = readBody(requestCaptor.getValue());
                assertEquals("city=new+york", requestBody);
                assertEquals("application/x-www-form-urlencoded",
                                requestCaptor.getValue().headers().firstValue("Content-Type").orElse(""));
        }

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void executeRestReflectionEscapesTemplateVariablesForJsonContentType() throws Exception {
                ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                                "REST Group", "desc", "REST", "", List.of(), List.of()));
                reflectionService.createBinding("alice", group.uuid(),
                                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

                reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                                "postJson",
                                "Post Json",
                                "desc",
                                group.uuid(),
                                List.of(new ReflectionInputParameter("note", "string", "Note", true)),
                                "POST",
                                "https://example.com/json",
                                Map.of(),
                                Map.of(),
                                "{\"note\":\"{{note}}\"}",
                                "application/json"));

                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200);
                when(response.body()).thenReturn("ok");
                when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
                when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

                reflectionService.executeRestReflection("postJson", Map.of("note", "He said \"hello\""), null, "alice");

                var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
                org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

                String requestBody = readBody(requestCaptor.getValue());
                assertEquals("{\"note\":\"He said \\\"hello\\\"\"}", requestBody);
                assertEquals("application/json",
                                requestCaptor.getValue().headers().firstValue("Content-Type").orElse(""));
        }

        private static String readBody(HttpRequest request) throws Exception {
                var publisherOpt = request.bodyPublisher();
                if (publisherOpt.isEmpty()) {
                        return "";
                }

                CompletableFuture<byte[]> future = new CompletableFuture<>();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

                publisherOpt.get().subscribe(new Flow.Subscriber<ByteBuffer>() {
                        private Flow.Subscription subscription;

                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                                this.subscription = subscription;
                                this.subscription.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(ByteBuffer item) {
                                byte[] bytes = new byte[item.remaining()];
                                item.get(bytes);
                                out.write(bytes, 0, bytes.length);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                                future.completeExceptionally(throwable);
                        }

                        @Override
                        public void onComplete() {
                                future.complete(out.toByteArray());
                        }
                });

                return new String(future.get(), StandardCharsets.UTF_8);
        }
}
