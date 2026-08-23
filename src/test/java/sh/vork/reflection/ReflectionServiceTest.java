package sh.vork.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Method;
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
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

import org.bson.Document;
import org.bson.conversions.Bson;

import sh.vork.ai.security.SkillSecretSubstitutor;
import sh.vork.oauth.OAuthTemplate;
import sh.vork.oauth.OAuthClientService;
import sh.vork.oauth.OAuthTemplateService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.security.SecureCredentialStore;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.TypeRecordBindingScope;
import sh.vork.typegen.TypeRecordVersionMetadata;

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
        DatabaseRepository<RecordReflection> recordReflectionRepository = new MapDatabaseRepository<>(RecordReflection.class);
        DatabaseRepository<MongoReflection> mongoReflectionRepository = new MapDatabaseRepository<>(MongoReflection.class);
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
                Map.of(),
                sh.vork.oauth.ArtifactStatus.SNAPSHOT);
        when(oauthTemplateService.getTemplate(any(UUID.class))).thenReturn(oauthTemplate);

        SkillSecretSubstitutor skillSecretSubstitutor = mock(SkillSecretSubstitutor.class);
        when(skillSecretSubstitutor.substitute(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        secureCredentialStore = mock(SecureCredentialStore.class);

        httpClient = mock(HttpClient.class);

        reflectionService = new ReflectionService(
                reflectionRepository,
                recordReflectionRepository,
                mongoReflectionRepository,
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

        when(secureCredentialStore.getGlobalSecret(
                eq("REFLECTION_BINDING:" + group.uuid() + ":default:API_KEY")))
                .thenReturn("copied-secret-value");

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest(
                        "sandbox",
                        null,
                        "",
                        Map.of(),
                        Map.of(),
                        "default"));

        verify(secureCredentialStore).saveGlobalSecret(
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

        when(secureCredentialStore.getGlobalSecret(
                eq("REFLECTION_BINDING:" + group.uuid() + ":default:API_KEY")))
                .thenReturn("copied-secret-value");

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest(
                        "sandbox",
                        null,
                        "",
                        Map.of(),
                        Map.of(),
                        "default"));

        verify(secureCredentialStore).saveGlobalSecret(
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
    void executeRestReflectionDropsEmptyOptionalUrlTemplateParameter() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "getCalendarEvents",
                "Get Calendar Events",
                "desc",
                group.uuid(),
                List.of(new ReflectionInputParameter("maxResults", "int", "Maximum results", false)),
                "GET",
                "https://www.googleapis.com/calendar/v3/calendars/primary/events?orderBy=startTime&maxResults={{maxResults}}&singleEvents=true",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                ""));

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        String result = reflectionService.executeRestReflection("getCalendarEvents", Map.of(), null, "alice");

        assertTrue(result.contains("\"status\":\"ok\""));

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        String calledUrl = requestCaptor.getValue().uri().toString();
        assertTrue(!calledUrl.contains("maxResults="));
        assertTrue(!calledUrl.contains("{{"));
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

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
        void executeRestReflectionOmitsMissingOptionalJsonTemplateFieldsAndArrayObjects() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "createCalendarEvent",
                "Create Calendar Event",
                "desc",
                group.uuid(),
                List.of(
                        new ReflectionInputParameter("summary", "string", "Summary", true),
                        new ReflectionInputParameter("location", "string", "Location", false),
                        new ReflectionInputParameter("attendeeEmail", "string", "Attendee email", false)),
                "POST",
                "https://example.com/calendar/events",
                Map.of(),
                Map.of(),
                "{\"summary\":\"{{summary}}\",\"location\":\"{{location}}\",\"attendees\":[{\"email\":\"{{attendeeEmail}}\"}]}",
                "application/json",
                "application/json",
                ""));

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("ok");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        reflectionService.executeRestReflection("createCalendarEvent", Map.of("summary", "Meeting with Lee and Bob"), null, "alice");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        String requestBody = readBody(requestCaptor.getValue());
        assertTrue(!requestBody.contains("{{"));
        assertTrue(!requestBody.contains("\"location\""));
        assertTrue(requestBody.contains("\"attendees\":[]"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeRestReflectionReplacesMissingOptionalArrayTemplateParameterWithEmptyArray() throws Exception {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "REST Group", "desc", "REST", "", List.of(), List.of()));

        reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                "createCalendarEventWithTags",
                "Create Calendar Event With Tags",
                "desc",
                group.uuid(),
                List.of(
                        new ReflectionInputParameter("summary", "string", "Summary", true),
                        new ReflectionInputParameter("tags", "string", "Tags", false, true)),
                "POST",
                "https://example.com/calendar/events",
                Map.of(),
                Map.of(),
                "{\"summary\":\"{{summary}}\",\"tags\":\"{{tags}}\"}",
                "application/json",
                "application/json",
                ""));

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("ok");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);

        reflectionService.executeRestReflection("createCalendarEventWithTags", Map.of("summary", "Meeting with Lee and Bob"), null, "alice");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        String requestBody = readBody(requestCaptor.getValue());
        assertTrue(!requestBody.contains("{{"));
        assertTrue(requestBody.contains("\"tags\":[]"));
    }

        @Test
        void createReflectionAllowsCustomRecordSearchTools() {
                reflectionService.ensureRecordReflectionsForType("sh.vork.generated.Customer");

                ReflectionGroup group = reflectionService.listGroups().stream()
                        .filter(g -> g.type() == ReflectionType.RECORD)
                        .findFirst()
                        .orElseThrow();

                ReflectionService.ReflectionRequest request = new ReflectionService.ReflectionRequest(
                                "recordSearchCustomerByName",
                                "Search Customer By Name",
                                "Custom SQL search",
                                group.uuid(),
                                List.of(new ReflectionInputParameter("query", "string", "query", true)),
                                "POST",
                                "",
                                Map.of(),
                                Map.of(),
                                "",
                                "application/json",
                                "application/json",
                                "{\"x-vork-record-tool\":true,\"recordFqn\":\"sh.vork.generated.Customer\",\"operation\":\"SEARCH\"}");

                Reflection created = reflectionService.createReflection(request);

                assertNotNull(created);
                assertEquals("recordSearchCustomerByName", created.id());
                assertTrue(created.outputSchema().contains("\"x-vork-record-tool\":true"));
                assertTrue(created.outputSchema().contains("\"recordFqn\":\"sh.vork.generated.Customer\""));
                assertTrue(created.outputSchema().contains("\"operation\":\"SEARCH\""));
        }

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void executeCustomRecordSearchPaginatesWithoutDeclaredPagingParameters() throws Exception {
                reflectionService.ensureRecordReflectionsForType("sh.vork.generated.Customer");

                ReflectionGroup group = reflectionService.listGroups().stream()
                        .filter(g -> g.type() == ReflectionType.RECORD)
                        .findFirst()
                        .orElseThrow();

                Reflection custom = reflectionService.createReflection(new ReflectionService.ReflectionRequest(
                        "recordSearchCustomerPaged",
                        "Search Customer Paged",
                        "Custom SQL search without declared paging inputs",
                        group.uuid(),
                        List.of(),
                        "POST",
                        "",
                        Map.of(),
                        Map.of(),
                        "",
                        "application/json",
                        "application/json",
                        "{\"x-vork-record-tool\":true,\"recordFqn\":\"sh.vork.generated.Customer\",\"operation\":\"SEARCH\"}"));

                assertNotNull(custom);
                assertTrue(custom.inputParameters().isEmpty());

                ReflectionBinding binding = reflectionService.getBinding(group.uuid(), "default");
                assertNotNull(binding);

                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                DatabaseRepository<TypeRecordBindingScope> scopeRepo = new MapDatabaseRepository<>(TypeRecordBindingScope.class);
                DatabaseRepository<TypeRecordVersionMetadata> versionRepo = new MapDatabaseRepository<>(TypeRecordVersionMetadata.class);

                reflectionService.setTypeDatabaseService(typeDatabaseService);
                reflectionService.setJavaTypeClassLoader(classLoader);
                reflectionService.setTypeRecordBindingScopeRepository(scopeRepo);
                reflectionService.setTypeRecordVersionMetadataRepository(versionRepo);

                try {
                        when(classLoader.loadClass("sh.vork.generated.Customer")).thenAnswer(invocation -> CustomerRecord.class);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                CustomerRecord c1 = new CustomerRecord("cust-1", "Alpha");
                CustomerRecord c2 = new CustomerRecord("cust-2", "Beta");
                CustomerRecord c3 = new CustomerRecord("cust-3", "Gamma");

                scopeRepo.save(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::cust-1",
                        "sh.vork.generated.Customer",
                        "cust-1",
                        binding.uuid(),
                        binding.name(),
                        1L,
                        1L));
                scopeRepo.save(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::cust-2",
                        "sh.vork.generated.Customer",
                        "cust-2",
                        binding.uuid(),
                        binding.name(),
                        1L,
                        1L));
                scopeRepo.save(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::cust-3",
                        "sh.vork.generated.Customer",
                        "cust-3",
                        binding.uuid(),
                        binding.name(),
                        1L,
                        1L));

                when(typeDatabaseService.searchBySql(
                        eq(CustomerRecord.class),
                        eq("name LIKE '%'"),
                        eq(0),
                        eq(Integer.MAX_VALUE),
                        eq("uuid"),
                        eq(sh.vork.orm.SortOrder.ASC)))
                        .thenReturn((java.util.stream.Stream) List.of(c1, c2, c3).stream());

                String result = reflectionService.executeRestReflection(
                        custom.id(),
                        Map.of(
                                "query", "name LIKE '%'",
                                "page", 1,
                                "pageSize", 1),
                        "default",
                        "alice");

                assertTrue(result.contains("\"status\":\"ok\""));
                assertTrue(result.contains("\"operation\":\"search\""));
                assertTrue(result.contains("\"total\":3"));
                assertTrue(result.contains("\"page\":1"));
                assertTrue(result.contains("\"pageSize\":1"));
                assertTrue(result.contains("\"uuid\":\"cust-2\""));

                verify(typeDatabaseService).searchBySql(
                        eq(CustomerRecord.class),
                        eq("name LIKE '%'"),
                        eq(0),
                        eq(Integer.MAX_VALUE),
                        eq("uuid"),
                        eq(sh.vork.orm.SortOrder.ASC));
        }

            @Test
            void executeRecordReflectionUsesDatabaseEngineAndNotHttpUrl() {
                reflectionService.ensureRecordReflectionsForType("sh.vork.generated.Customer");

                Reflection reflection = reflectionService.listReflections().stream()
                        .filter(r -> r.outputSchema().contains("\"recordFqn\":\"sh.vork.generated.Customer\""))
                        .filter(r -> r.outputSchema().contains("\"operation\":\"CREATE\""))
                        .findFirst()
                        .orElseThrow();

                ReflectionGroup group = reflectionService.getGroup(reflection.groupUuid());

                ReflectionBinding binding = reflectionService.getBinding(group.uuid(), "default");
                assertNotNull(binding);

                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                DatabaseRepository<TypeRecordBindingScope> scopeRepo = new MapDatabaseRepository<>(TypeRecordBindingScope.class);
                DatabaseRepository<TypeRecordVersionMetadata> versionRepo = new MapDatabaseRepository<>(TypeRecordVersionMetadata.class);
                DatabaseRepository<JavaType> javaTypeRepo = new MapDatabaseRepository<>(JavaType.class);
                javaTypeRepo.save(new JavaType("sh.vork.generated.Customer", "package sh.vork.generated; public record Customer(String uuid, String name) {}", Map.of(), 1L, 99L));
                reflectionService.setTypeDatabaseService(typeDatabaseService);
                reflectionService.setJavaTypeClassLoader(classLoader);
                reflectionService.setTypeRecordBindingScopeRepository(scopeRepo);
                reflectionService.setTypeRecordVersionMetadataRepository(versionRepo);
                reflectionService.setJavaTypeRepository(javaTypeRepo);

                try {
                        when(classLoader.loadClass("sh.vork.generated.Customer")).thenAnswer(invocation -> CustomerRecord.class);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                String result = reflectionService.executeRestReflection(
                        reflection.id(),
                        Map.of("name", "3SP Ltd"),
                        "default",
                        "alice");

                assertTrue(result.contains("\"status\":\"ok\""));
                                assertTrue(result.contains("\"revision\":1"));
                                assertTrue(result.contains("\"schemaVersion\":99"));
                verify(typeDatabaseService, never()).get(eq(CustomerRecord.class), eq("cust-1"));

                org.mockito.ArgumentCaptor<CustomerRecord> entityCaptor = org.mockito.ArgumentCaptor.forClass(CustomerRecord.class);
                verify(typeDatabaseService).save(entityCaptor.capture());
                ReflectionServiceTest.CustomerRecord savedEntity = entityCaptor.getValue();
                String generatedUuid = savedEntity.uuid();
                assertNotNull(generatedUuid);
                assertTrue(!generatedUuid.isBlank());

                TypeRecordBindingScope scope = scopeRepo.get("sh.vork.generated.Customer::" + generatedUuid);
                assertNotNull(scope);
                assertEquals(binding.uuid(), scope.bindingUuid());
                                TypeRecordVersionMetadata version = versionRepo.get("sh.vork.generated.Customer::" + generatedUuid);
                                assertNotNull(version);
                                assertEquals(1L, version.entityRevision());
                                assertEquals(99L, version.schemaVersion());
            }

            @Test
            void executeRecordReflectionDeniesCrossBindingWrite() {
                reflectionService.ensureRecordReflectionsForType("sh.vork.generated.Customer");

                Reflection reflection = reflectionService.listReflections().stream()
                        .filter(r -> r.outputSchema().contains("\"recordFqn\":\"sh.vork.generated.Customer\""))
                        .filter(r -> r.outputSchema().contains("\"operation\":\"UPDATE\""))
                        .findFirst()
                        .orElseThrow();

                ReflectionGroup group = reflectionService.getGroup(reflection.groupUuid());

                ReflectionBinding bindingA = reflectionService.createBinding("alice", group.uuid(),
                        new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));
                reflectionService.createBinding("alice", group.uuid(),
                        new ReflectionService.ReflectionBindingRequest("sandbox", "", Map.of(), Map.of()));

                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                DatabaseRepository<TypeRecordBindingScope> scopeRepo = new MapDatabaseRepository<>(TypeRecordBindingScope.class);
                DatabaseRepository<TypeRecordVersionMetadata> versionRepo = new MapDatabaseRepository<>(TypeRecordVersionMetadata.class);
                scopeRepo.save(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::cust-1",
                        "sh.vork.generated.Customer",
                        "cust-1",
                        bindingA.uuid(),
                        bindingA.name(),
                        1L,
                        1L));

                reflectionService.setTypeDatabaseService(typeDatabaseService);
                reflectionService.setJavaTypeClassLoader(classLoader);
                reflectionService.setTypeRecordBindingScopeRepository(scopeRepo);
                reflectionService.setTypeRecordVersionMetadataRepository(versionRepo);

                try {
                        when(classLoader.loadClass("sh.vork.generated.Customer")).thenAnswer(invocation -> CustomerRecord.class);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                String result = reflectionService.executeRestReflection(
                        reflection.id(),
                        Map.of("uuid", "cust-1", "name", "New Name"),
                        "sandbox",
                        "alice");

                assertTrue(result.contains("different binding scope"));
                verify(typeDatabaseService, org.mockito.Mockito.never()).save(any());
            }

            @Test
            void executeRecordReflectionUpdateEnforcesExpectedRevision() {
                reflectionService.ensureRecordReflectionsForType("sh.vork.generated.Customer");

                Reflection reflection = reflectionService.listReflections().stream()
                        .filter(r -> r.outputSchema().contains("\"recordFqn\":\"sh.vork.generated.Customer\""))
                        .filter(r -> r.outputSchema().contains("\"operation\":\"UPDATE\""))
                        .findFirst()
                        .orElseThrow();

                ReflectionGroup group = reflectionService.getGroup(reflection.groupUuid());
                ReflectionBinding binding = reflectionService.getBinding(group.uuid(), "default");
                assertNotNull(binding);

                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                DatabaseRepository<TypeRecordBindingScope> scopeRepo = new MapDatabaseRepository<>(TypeRecordBindingScope.class);
                DatabaseRepository<TypeRecordVersionMetadata> versionRepo = new MapDatabaseRepository<>(TypeRecordVersionMetadata.class);
                scopeRepo.save(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::cust-1",
                        "sh.vork.generated.Customer",
                        "cust-1",
                        binding.uuid(),
                        binding.name(),
                        1L,
                        1L));
                versionRepo.save(new TypeRecordVersionMetadata(
                        "sh.vork.generated.Customer::cust-1",
                        "sh.vork.generated.Customer",
                        "cust-1",
                        77L,
                        3L,
                        binding.uuid(),
                        binding.uuid(),
                        1L,
                        1L));

                reflectionService.setTypeDatabaseService(typeDatabaseService);
                reflectionService.setJavaTypeClassLoader(classLoader);
                reflectionService.setTypeRecordBindingScopeRepository(scopeRepo);
                reflectionService.setTypeRecordVersionMetadataRepository(versionRepo);

                try {
                    when(classLoader.loadClass("sh.vork.generated.Customer")).thenAnswer(invocation -> CustomerRecord.class);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                String mismatch = reflectionService.executeRestReflection(
                        reflection.id(),
                        Map.of("uuid", "cust-1", "name", "New Name", "expectedRevision", 2),
                        "default",
                        "alice");
                assertTrue(mismatch.contains("Revision mismatch"));
                verify(typeDatabaseService, org.mockito.Mockito.never()).save(any());

                String success = reflectionService.executeRestReflection(
                        reflection.id(),
                        Map.of("uuid", "cust-1", "name", "New Name", "expectedRevision", 3),
                        "default",
                        "alice");
                assertTrue(success.contains("\"status\":\"ok\""));
                assertTrue(success.contains("\"revision\":4"));
                verify(typeDatabaseService).save(any(CustomerRecord.class));
            }

        @Test
        void ensureRecordReflectionsForTypeCreatesRecordGroupAndMandatoryTools() {
                reflectionService.ensureRecordReflectionsForType("sh.vork.generated.CustomerRecord");

                List<ReflectionGroup> recordGroups = reflectionService.listGroups().stream()
                                .filter(group -> group.type() == ReflectionType.RECORD)
                                .toList();
                assertEquals(1, recordGroups.size());

                ReflectionGroup group = recordGroups.getFirst();
                assertEquals("record", group.groupId());
                assertEquals("CustomerRecord", group.artifactId());
                assertEquals("SNAPSHOT", group.version());
                List<Reflection> tools = reflectionService.reflectionsForGroup(group.uuid());
                assertEquals(5, tools.size());
                assertTrue(tools.stream().allMatch(tool -> tool.outputSchema().contains("x-vork-mandatory-record-tool")));
                assertTrue(tools.stream().allMatch(tool -> tool.url().isBlank()));
                assertTrue(tools.stream().allMatch(tool -> tool.headers().isEmpty()));
                ReflectionBinding defaultBinding = reflectionService.getBinding(group.uuid(), "default");
                assertNotNull(defaultBinding);
        }

        @Test
        void ensureRecordReflectionsForEnumCreatesListToolOnly() {
                reflectionService.ensureRecordReflectionsForType("java.time.DayOfWeek");

                List<Reflection> tools = reflectionService.listReflections().stream()
                                .filter(tool -> tool.outputSchema().contains("\"recordFqn\":\"java.time.DayOfWeek\""))
                                .toList();

                assertEquals(1, tools.size());
                Reflection listTool = tools.getFirst();
                assertTrue(listTool.outputSchema().contains("\"operation\":\"LIST\""));
                assertTrue(listTool.id().startsWith("list"));
        }

        @Test
        void ensureRecordReflectionsForRecordIncludesFieldInputsForCreateAndUpdate() {
                reflectionService.ensureRecordReflectionsForType(CustomerRecord.class.getName());

                Reflection createTool = reflectionService.listReflections().stream()
                                .filter(tool -> tool.outputSchema().contains("\"recordFqn\":\"" + CustomerRecord.class.getName() + "\""))
                                .filter(tool -> tool.outputSchema().contains("\"operation\":\"CREATE\""))
                                .findFirst()
                                .orElseThrow();

                Reflection updateTool = reflectionService.listReflections().stream()
                                .filter(tool -> tool.outputSchema().contains("\"recordFqn\":\"" + CustomerRecord.class.getName() + "\""))
                                .filter(tool -> tool.outputSchema().contains("\"operation\":\"UPDATE\""))
                                .findFirst()
                                .orElseThrow();

                assertTrue(createTool.inputParameters().stream().noneMatch(parameter -> "uuid".equals(parameter.name())));
                assertTrue(createTool.inputParameters().stream().anyMatch(parameter -> "name".equals(parameter.name())));
                assertTrue(createTool.inputParameters().stream().anyMatch(parameter -> "record".equals(parameter.name())));
                assertTrue(updateTool.inputParameters().stream().anyMatch(parameter -> "uuid".equals(parameter.name()) && parameter.required()));
                assertTrue(updateTool.inputParameters().stream().anyMatch(parameter -> "expectedRevision".equals(parameter.name())));
        }

        @Test
        void mandatoryRecordReflectionsAreImmutableForUpdateAndDelete() {
                reflectionService.ensureRecordReflectionsForType("sh.vork.generated.CustomerRecord");

                Reflection mandatory = reflectionService.listReflections().stream()
                                .filter(reflection -> reflection.outputSchema().contains("x-vork-mandatory-record-tool"))
                                .findFirst()
                                .orElseThrow();

                ReflectionService.ReflectionRequest request = new ReflectionService.ReflectionRequest(
                                mandatory.id(),
                                mandatory.name(),
                                mandatory.description(),
                                mandatory.groupUuid(),
                                mandatory.inputParameters(),
                                mandatory.method(),
                                mandatory.url(),
                                mandatory.headers(),
                                mandatory.queryParameters(),
                                mandatory.bodyTemplate(),
                                mandatory.requestContentType(),
                                mandatory.responseContentType(),
                                mandatory.outputSchema());

                assertThrows(IllegalArgumentException.class, () -> reflectionService.updateReflection(mandatory.uuid(), request));
                assertThrows(IllegalArgumentException.class, () -> reflectionService.deleteReflection(mandatory.uuid()));
        }

    @Test
    @SuppressWarnings("unchecked")
    void executeMongoCreateInsertsDocumentAndReturnsOkPayload() throws Exception {
        MongoCollection<Document> collection = mock(MongoCollection.class);

        String result = invokeMongoOperation("executeMongoCreate",
                "vork",
                "customers",
                collection,
                Map.of("document", "{\"name\":\"Acme\"}"));

        verify(collection).insertOne(any(Document.class));
        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"operation\":\"create\""));
        assertTrue(result.contains("\"collection\":\"customers\""));
        assertTrue(result.contains("\"name\":\"Acme\""));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeMongoReadReturnsNotFoundWhenDocumentMissing() throws Exception {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> iterable = mock(FindIterable.class);
                when(collection.find(any(Bson.class))).thenReturn((FindIterable) iterable);
        when(iterable.first()).thenReturn(null);

        String result = invokeMongoOperation("executeMongoRead",
                "vork",
                "customers",
                collection,
                Map.of("uuid", "cust-1"));

        assertTrue(result.contains("\"status\":\"not_found\""));
        assertTrue(result.contains("\"operation\":\"read\""));
        assertTrue(result.contains("\"uuid\":\"cust-1\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeMongoUpdateReturnsNotFoundWhenNoRowsModified() throws Exception {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getModifiedCount()).thenReturn(0L);
                when(collection.replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class))).thenReturn(updateResult);

        String result = invokeMongoOperation("executeMongoUpdate",
                "vork",
                "customers",
                collection,
                Map.of("uuid", "cust-1", "document", "{\"name\":\"Updated\"}"));

        assertTrue(result.contains("\"status\":\"not_found\""));
        assertTrue(result.contains("\"operation\":\"update\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeMongoDeleteReturnsDeletedCountAndOkStatus() throws Exception {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(collection.deleteOne(any())).thenReturn(deleteResult);

        String result = invokeMongoOperation("executeMongoDelete",
                "vork",
                "customers",
                collection,
                Map.of("uuid", "cust-1"));

        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"operation\":\"delete\""));
        assertTrue(result.contains("\"deletedCount\":1"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executeMongoSearchReturnsPagedResultsForMongoQuery() throws Exception {
        MongoCollection<Document> collection = mock(MongoCollection.class);
        FindIterable<Document> iterable = mock(FindIterable.class);

        when(collection.countDocuments(any(Document.class))).thenReturn(2L);
        when(collection.find(any(Document.class))).thenReturn((FindIterable) iterable);
        when(iterable.sort(any(Document.class))).thenReturn((FindIterable) iterable);
        when(iterable.skip(any(Integer.class))).thenReturn((FindIterable) iterable);
        when(iterable.limit(any(Integer.class))).thenReturn((FindIterable) iterable);
                when(iterable.into(any(List.class))).thenAnswer(invocation -> {
                        List<Document> target = invocation.getArgument(0);
            target.add(new Document("_id", "cust-1").append("name", "Acme"));
            return target;
        });

        String result = invokeMongoOperation("executeMongoSearch",
                "vork",
                "customers",
                collection,
                Map.of(
                        "query", "{\"name\":\"Acme\"}",
                        "queryType", "MONGO",
                        "sortField", "name",
                        "sortOrder", "ASC",
                        "page", 0,
                        "pageSize", 20));

        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"operation\":\"search\""));
        assertTrue(result.contains("\"total\":2"));
        assertTrue(result.contains("\"name\":\"Acme\""));
    }

    @Test
    void executeMongoReflectionUsesBindingSecretUriWhenRuntimeUriMissing() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "Mongo Group",
                "desc",
                "MONGO",
                "",
                List.of(new sh.vork.skill.SkillSecret("MONGO_URI", "Mongo URI")),
                List.of()));

        reflectionService.createBinding("alice", group.uuid(),
                new ReflectionService.ReflectionBindingRequest("default", "", Map.of(), Map.of()));

        Reflection reflection = new Reflection(
                UUID.randomUUID().toString(),
                "getMongoCustomer",
                "Get Mongo Customer",
                "desc",
                group.uuid(),
                List.of(new ReflectionInputParameter("uuid", "string", "Document id", true)),
                "POST",
                "/mongodb/tools/getMongoCustomer",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                "{\"x-vork-mandatory-mongo-tool\":true,\"database\":\"vork\",\"collection\":\"customers\",\"operation\":\"READ\"}",
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        when(secureCredentialStore.getGlobalSecret(eq("REFLECTION_BINDING:" + group.uuid() + ":default:MONGO_URI")))
                .thenReturn("mongodb://");

        String result = invokeMongoReflection(reflection, Map.of("uuid", "cust-1"), "default", "alice");

        verify(secureCredentialStore).getGlobalSecret(eq("REFLECTION_BINDING:" + group.uuid() + ":default:MONGO_URI"));
        assertTrue(!result.contains("Mongo connection URI is missing for this binding"));
        assertTrue(result.contains("\"status\":\"error\""));
    }

    @Test
    void createMongoToolReflectionCreatesCustomMongoTool() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "Mongo Group",
                "desc",
                "MONGO",
                "",
                true,
                List.of(),
                List.of(),
                "NONE",
                "",
                "mongogroup",
                "tools"));

        Reflection created = reflectionService.createMongoToolReflection(new ReflectionService.MongoToolRequest(
                "getMongoOrders",
                "Get Mongo Orders",
                "Read orders",
                group.uuid(),
                "vork",
                "orders",
                "READ",
                "",
                "",
                ""));

        assertEquals("getMongoOrders", created.id());
        assertEquals(group.uuid(), created.groupUuid());
        assertTrue(created.outputSchema().contains("\"x-vork-mongo-tool\":true"));
        assertTrue(created.outputSchema().contains("\"collection\":\"orders\""));
    }

    @Test
    void updateMongoToolReflectionUpdatesCustomMongoTool() {
        ReflectionGroup group = reflectionService.createGroup(new ReflectionService.ReflectionGroupRequest(
                "Mongo Group",
                "desc",
                "MONGO",
                "",
                true,
                List.of(),
                List.of(),
                "NONE",
                "",
                "mongogroup2",
                "tools"));

        Reflection created = reflectionService.createMongoToolReflection(new ReflectionService.MongoToolRequest(
                "searchMongoOrders",
                "Search Mongo Orders",
                "Search orders",
                group.uuid(),
                "vork",
                "orders",
                "SEARCH",
                "",
                "SQL",
                "status = 'ACTIVE'"));

        Reflection updated = reflectionService.updateMongoToolReflection(created.uuid(), new ReflectionService.MongoToolRequest(
                "searchMongoOrders",
                "Search Mongo Orders Updated",
                "Search orders updated",
                group.uuid(),
                "vork",
                "orders_v2",
                "READ",
                "",
                "",
                ""));

        assertNotNull(updated);
        assertEquals(created.uuid(), updated.uuid());
        assertEquals("Search Mongo Orders Updated", updated.name());
        assertTrue(updated.outputSchema().contains("\"collection\":\"orders_v2\""));
        assertTrue(updated.outputSchema().contains("\"operation\":\"READ\""));
    }

    private String invokeMongoOperation(String methodName,
                                        String database,
                                        String collectionName,
                                        MongoCollection<Document> collection,
                                        Map<String, Object> inputs) throws Exception {
        Method method;
        Object result;
        if ("executeMongoSearch".equals(methodName)) {
            method = ReflectionService.class.getDeclaredMethod(
                    methodName,
                    String.class,
                    String.class,
                    MongoCollection.class,
                    Map.class,
                    Reflection.class,
                    Class.forName("sh.vork.reflection.ReflectionService$MongoToolMetadata"));
            method.setAccessible(true);
            result = method.invoke(reflectionService, database, collectionName, collection, inputs, null, null);
        } else {
            method = ReflectionService.class.getDeclaredMethod(
                    methodName,
                    String.class,
                    String.class,
                    MongoCollection.class,
                    Map.class);
            method.setAccessible(true);
            result = method.invoke(reflectionService, database, collectionName, collection, inputs);
        }
        return (String) result;
    }

        private String invokeMongoReflection(Reflection reflection,
                                                                                 Map<String, Object> runtimeInputs,
                                                                                 String bindingName,
                                                                                 String username) {
                try {
                        Method method = ReflectionService.class.getDeclaredMethod(
                                        "executeMongoReflection",
                                        Reflection.class,
                                        Map.class,
                                        String.class,
                                        String.class);
                        method.setAccessible(true);
                        return (String) method.invoke(reflectionService, reflection, runtimeInputs, bindingName, username);
                } catch (Exception ex) {
                        throw new RuntimeException(ex);
                }
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

        private record CustomerRecord(String uuid, String name) implements sh.vork.orm.DatabaseEntity {}
}
