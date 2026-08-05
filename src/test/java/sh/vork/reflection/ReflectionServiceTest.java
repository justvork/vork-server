package sh.vork.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.security.SkillSecretSubstitutor;
import sh.vork.oauth.OAuthTemplate;
import sh.vork.oauth.OAuthClientService;
import sh.vork.oauth.OAuthTemplateService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.security.SecureCredentialStore;

class ReflectionServiceTest {

    private ReflectionService reflectionService;
    private HttpClient httpClient;
        private SecureCredentialStore secureCredentialStore;
        private OAuthClientService oauthClientService;
        private OAuthTemplateService oauthTemplateService;
        private OAuthTemplate oauthTemplate;

    @BeforeEach
    void setUp() {
        DatabaseRepository<Reflection> reflectionRepository = new MapDatabaseRepository<>(Reflection.class);
        DatabaseRepository<ReflectionGroup> groupRepository = new MapDatabaseRepository<>(ReflectionGroup.class);
        DatabaseRepository<ReflectionBinding> bindingRepository = new MapDatabaseRepository<>(ReflectionBinding.class);
        DatabaseRepository<PendingOAuthBindingAction> pendingOauthBindingActionRepository =
                new MapDatabaseRepository<>(PendingOAuthBindingAction.class);

        oauthClientService = mock(OAuthClientService.class);
        when(oauthClientService.resolveHeaderValue(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(oauthClientService.resolveAccessToken(any(), any(), any())).thenReturn(null);

        oauthTemplateService = mock(OAuthTemplateService.class);
        oauthTemplate = new OAuthTemplate(
                UUID.randomUUID(),
                "GitHub Work",
                "github",
                "GitHub OAuth",
                URI.create("https://github.com/login/oauth/authorize"),
                URI.create("https://github.com/login/oauth/access_token"),
                List.of("repo"),
                Map.of());
        when(oauthTemplateService.getTemplate(any(UUID.class))).thenReturn(oauthTemplate);

        SkillSecretSubstitutor skillSecretSubstitutor = mock(SkillSecretSubstitutor.class);
        when(skillSecretSubstitutor.substitute(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        secureCredentialStore = mock(SecureCredentialStore.class);

        httpClient = mock(HttpClient.class);

        reflectionService = new ReflectionService(
                reflectionRepository,
                groupRepository,
                bindingRepository,
                pendingOauthBindingActionRepository,
                oauthClientService,
                oauthTemplateService,
                skillSecretSubstitutor,
                secureCredentialStore,
                new ObjectMapper(),
                httpClient);
    }

    @Test
    void createGroupRejectsOauthAuthenticationWithoutTemplate() {
        assertThrows(IllegalArgumentException.class, () ->
                reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                        "OAuth Group",
                        "desc",
                        "REST",
                        "",
                        true,
                        List.of(),
                        List.of(),
                        "OAUTH",
                        "")));
    }

    @Test
    void createBindingRequiresConnectedOauthProfileWhenGroupUsesOauthAuthentication() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "OAuth Group",
                "desc",
                "REST",
                "",
                true,
                List.of(),
                List.of(),
                "OAUTH",
                oauthTemplate.id().toString()));

        when(oauthClientService.resolveAccessToken("alice", "github", "default")).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                reflectionService.createBinding("alice", group.uuid(),
                        new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of())));

        assertTrue(ex.getMessage().contains("OAuth is required before creating binding"));
        assertTrue(ex.getMessage().contains("/api/oauth-templates/"));
    }

    @Test
    void saveBindingWithOAuthFlowReturnsConnectRequiredAndCompletesAfterCallback() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "OAuth Group",
                "desc",
                "REST",
                "",
                true,
                List.of(),
                List.of(),
                "OAUTH",
                oauthTemplate.id().toString()));

        when(oauthClientService.connectOrEnsure(eq("alice"), any()))
                .thenReturn(Map.of(
                        "status", "connect_required",
                        "state", "oauth-state-1",
                        "authorizationUrl", "https://oauth.example/authorize"));

        ReflectionService.BindingSaveOutcome start = reflectionService.saveBindingWithOAuthFlow(
                "alice",
                group.uuid(),
                "",
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        assertEquals("connect_required", start.status());
        assertEquals("https://oauth.example/authorize", start.authorizationUrl());

        when(oauthClientService.resolveAccessToken("alice", "github", "default"))
                .thenReturn("oauth-token");

        ReflectionService.PendingOAuthBindingCompletion completion =
                reflectionService.completePendingOAuthBinding("oauth-state-1");

        assertTrue(completion.handled());
        assertTrue(completion.success());
        assertNotNull(reflectionService.getBinding(group.uuid(), "default"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionOauthOverridesAuthorizationHeader() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "OAuth Group",
                "desc",
                "REST",
                "",
                true,
                List.of(),
                List.of(),
                "OAUTH",
                oauthTemplate.id().toString()));

        when(oauthClientService.resolveAccessToken("alice", "github", "default"))
                .thenReturn("oauth-token");

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "getOAuthWeather",
                "OAuth Weather",
                "desc",
                group.uuid(),
                List.of(),
                "GET",
                "https://example.com/weather",
                Map.of("Authorization", "Bearer static-token"),
                Map.of(),
                "",
                "application/json",
                "application/json",
                ""));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection("getOAuthWeather", Map.of(), null, "alice");
        assertTrue(result.contains("\"status\":\"ok\""));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("Bearer oauth-token", requestCaptor.getValue().headers().firstValue("Authorization").orElse(""));
    }

    @Test
    void deleteBindingDeletesMappedOauthProfile() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "OAuth Group",
                "desc",
                "REST",
                "",
                true,
                List.of(),
                List.of(),
                "OAUTH",
                oauthTemplate.id().toString()));

        when(oauthClientService.resolveAccessToken("alice", "github", "default"))
                .thenReturn("oauth-token-default");
        when(oauthClientService.resolveAccessToken("alice", "github", "sandbox"))
                .thenReturn("oauth-token-sandbox");

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("sandbox", "", Map.of(), Map.of()));

        reflectionService.deleteBinding("alice", group.uuid(), "sandbox");

        verify(oauthClientService).deleteProfile("alice", "github", "sandbox");
    }

    @Test
    void deleteDefaultBindingRemovesDefaultBinding() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        boolean deleted = reflectionService.deleteBinding("alice", group.uuid(), "default");

        assertTrue(deleted);
        ReflectionBinding currentDefault = reflectionService.getBinding(group.uuid(), "default");
                assertNull(currentDefault);
        assertEquals(0, reflectionService.bindingsForGroup(group.uuid()).size());
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
                "application/json",
                "application/json",
                "");

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
                "application/json",
                "application/json",
                ""));

        String result = reflectionService.executeRestReflection("WeatherLookup", Map.of(), null, "alice");

        assertTrue(result.contains("missing_parameters"));
        assertTrue(result.contains("city"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionSchemaMismatchLogsWarningButReturnsSuccess() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "weatherSchemaCheck",
                "Weather Schema Check",
                "desc",
                group.uuid(),
                List.of(),
                "GET",
                "https://example.com/weather",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                "{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"string\"}}}"));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection("weatherSchemaCheck", Map.of(), null, "alice");

        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"statusCode\":200"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionResolvesUrlTemplateFromBindingParametersCaseInsensitive() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(),
                List.of(new ReflectionBindingParameter("SERVICE_HOST", "string", "host", ""))));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of("SERVICE_HOST", "api.openrouteservice.org"), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "DirectionsLookup",
                "Directions Lookup",
                "desc",
                group.uuid(),
                List.of(),
                "GET",
                "https://{{service_host}}/v2/directions/driving-car",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                ""));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection("DirectionsLookup", Map.of(), null, "alice");
        assertTrue(result.contains("\"status\":\"ok\""));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        String uri = requestCaptor.getValue().uri().toString();
        assertEquals("https://api.openrouteservice.org/v2/directions/driving-car", uri);
    }

    @Test
    void executeRestReflectionReturnsExplicitErrorForUnresolvedUrlTemplateParameters() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(),
                List.of(new ReflectionBindingParameter("service_host", "string", "host", ""))));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "DirectionsLookup2",
                "Directions Lookup",
                "desc",
                group.uuid(),
                List.of(),
                "GET",
                "https://{{service_host}}/v2/directions/driving-car",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                ""));

        String result = reflectionService.executeRestReflection("DirectionsLookup2", Map.of(), null, "alice");

        assertTrue(result.contains("\"status\":\"error\""));
        assertTrue(result.contains("Unresolved URL template parameter(s): service_host"));
        assertTrue(result.contains("retryAllowed"));
    }

        @Test
        void executeRestReflectionErrorPayloadInstructsNoRetry() {
                String result = reflectionService.executeRestReflection("missing-reflection", Map.of(), null, "alice");

                assertTrue(result.contains("\"status\":\"error\""));
                assertTrue(result.contains("\"retryAllowed\":false"));
                assertTrue(result.contains("do not retry with different binding or profile names"));
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
                                "application/json",
                "application/json",
                ""));

                String result = reflectionService.executeRestReflection("WeatherLookup", Map.of(), null, "alice");

                assertTrue(result.contains("\"status\":\"error\""));
                assertTrue(result.contains("No bindings configured"));
        }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
        void executeRestReflectionUsesOnlyConfiguredQueryParameters() throws Exception {
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
                "application/json",
                "application/json",
                ""));

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
        assertTrue(!calledUrl.contains("city=london"));
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
                "application/json",
                "application/json",
                ""));

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
                assertTrue(!calledUrl.contains("city=auckland"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionTreatsHostOnlyTemplateUrlAsHttpsAbsolute() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "locatePostcode",
                "Locate Postcode",
                "desc",
                group.uuid(),
                List.of(new ReflectionInputParameter("service_host", "string", "Service Host", true)),
                "GET",
                "{{service_host}}/geocode/search",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                ""));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection(
                "locatePostcode",
                Map.of("service_host", "api.openrouteservice.org"),
                null,
                "alice");

        assertTrue(result.contains("\"status\":\"ok\""));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        String calledUrl = requestCaptor.getValue().uri().toString();
        assertTrue(calledUrl.startsWith("https://api.openrouteservice.org/geocode/search"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionSupportsAbsoluteUrlTemplateWithSpaceInQueryAndNoBaseUrl() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));
        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "locatePostcodeAbsolute",
                "Locate Postcode Absolute",
                "desc",
                group.uuid(),
                List.of(new ReflectionInputParameter("postcode", "string", "Postcode", true)),
                "GET",
                "https://api.openrouteservice.org/geocode/search?text={{postcode}}",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                ""));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection(
                "locatePostcodeAbsolute",
                Map.of("postcode", "NG13 9HH"),
                null,
                "alice");

        assertTrue(result.contains("\"status\":\"ok\""));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        String calledUrl = requestCaptor.getValue().uri().toString();
        assertTrue(calledUrl.startsWith("https://api.openrouteservice.org/geocode/search?text=NG13%209HH"));
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
                "application/x-www-form-urlencoded",
                "application/json",
                ""));

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
                                "application/x-www-form-urlencoded",
                "application/json",
                ""));

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
                                "application/json",
                "application/json",
                ""));

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
