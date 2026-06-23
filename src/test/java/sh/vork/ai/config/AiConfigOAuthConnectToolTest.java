package sh.vork.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.function.OAuthConnectRequest;
import sh.vork.oauth.OAuthClientService;
import sh.vork.security.SecureCredentialStore;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

class AiConfigOAuthConnectToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void oauthConnect_ignoresModelSuppliedCredentials_andUsesContextAndSecretStore() throws Exception {
        JavaTypeClassLoader classLoader = org.mockito.Mockito.mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = org.mockito.Mockito.mock(TypeDatabaseService.class);
        OAuthClientService oauthClientService = org.mockito.Mockito.mock(OAuthClientService.class);
        SecureCredentialStore secureCredentialStore = org.mockito.Mockito.mock(SecureCredentialStore.class);

        when(secureCredentialStore.getSecretForUser("alice", "oauth.client.gmail.clientSecret"))
                .thenReturn("secret-from-store");
        when(oauthClientService.connectOrEnsure(eq("alice"), any(OAuthConnectRequest.class)))
                .thenReturn(Map.of("status", "ready", "clientName", "gmail"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        ToolExecutionContext.bindSessionUuid("session-oauth-test");
        ToolExecutionContext.put("oauth.client.gmail.clientId", "context-client-id");

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.oauthConnect(oauthClientService, secureCredentialStore);

        String toolInput = objectMapper.writeValueAsString(Map.of(
                "clientName", "gmail",
                "authorizeEndpoint", "https://accounts.google.com/o/oauth2/v2/auth",
                "tokenEndpoint", "https://oauth2.googleapis.com/token",
                "clientId", "model-client-id-should-be-ignored",
                "clientSecret", "model-client-secret-should-be-ignored",
                "redirectUri", "https://localhost:8443/api/oauth/callback",
                "scopes", List.of("openid", "email"),
                "authorizationParams", Map.of("access_type", "offline")
        ));

        String output = tool.call(toolInput);
        Map<String, Object> response = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {});
        assertEquals("ready", response.get("status"));

        ArgumentCaptor<OAuthConnectRequest> requestCaptor = ArgumentCaptor.forClass(OAuthConnectRequest.class);
        verify(oauthClientService).connectOrEnsure(eq("alice"), requestCaptor.capture());

        OAuthConnectRequest effective = requestCaptor.getValue();
        assertEquals("context-client-id", effective.clientId());
        assertEquals("secret-from-store", effective.clientSecret());
    }
}
