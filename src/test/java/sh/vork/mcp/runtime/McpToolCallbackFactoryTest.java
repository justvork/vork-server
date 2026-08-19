package sh.vork.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.mcp.client.McpClient;
import sh.vork.mcp.client.McpClientFactory;
import sh.vork.mcp.client.McpInvocationResult;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.model.McpBindingTool;
import sh.vork.mcp.model.McpToolParameterConfig;
import sh.vork.mcp.model.McpToolParameterInputMode;
import sh.vork.mcp.model.McpTransportMode;
import sh.vork.reflection.ArtifactStatus;
import sh.vork.security.SecureCredentialStore;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolCallbackFactoryTest {

        private McpToolCallbackFactory newFactory(SecureCredentialStore secureCredentialStore,
                                                                                          McpClientFactory clientFactory) {
                return newFactory(secureCredentialStore, clientFactory, new AuthorizationRuleEngine(Set.of()));
        }

        private McpToolCallbackFactory newFactory(SecureCredentialStore secureCredentialStore,
                                                                                          McpClientFactory clientFactory,
                                                                                          AuthorizationRuleEngine authorizationRuleEngine) {
                McpParameterResolutionService parameterResolutionService = new McpParameterResolutionService(secureCredentialStore);
                return new McpToolCallbackFactory(new ObjectMapper(), clientFactory, secureCredentialStore, parameterResolutionService, authorizationRuleEngine);
        }

    @Test
    void toolSchemaExposesOnlyAiVisibleParameters() {
        SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
        McpClientFactory clientFactory = mock(McpClientFactory.class);
                McpToolCallbackFactory factory = newFactory(secureCredentialStore, clientFactory);

        McpBinding binding = new McpBinding(
                "b1", "Demo Binding", McpBindingStatus.ACTIVE, "https://mcp.example", McpTransportMode.STREAMABLE_HTTP,
                "", "demo", "artifact", "1.0", ArtifactStatus.SNAPSHOT, 1L, "", "hash", 1L, 1L);

        McpBindingTool tool = new McpBindingTool(
                "t1", "b1", "search", "search", "Search endpoint", true, false,
                List.of(
                        new McpToolParameterConfig("query", "string", true, "q", "", McpToolParameterInputMode.AI_REQUIRED, ""),
                        new McpToolParameterConfig("apiToken", "string", true, "secret", "", McpToolParameterInputMode.SECRET, "secret.ref"),
                        new McpToolParameterConfig("approval", "string", false, "user", "", McpToolParameterInputMode.USER_ALWAYS_PROMPT, "")
                ),
                "schema");

        ToolCallback callback = factory.create(binding, tool);
        String schema = String.valueOf(callback.getToolDefinition().inputSchema());

        assertTrue(schema.contains("query"));
        assertFalse(schema.contains("apiToken"));
        assertFalse(schema.contains("approval"));
    }

    @Test
    void toolCallResolvesSecretParameterAndAuthorization() {
        SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
        McpClientFactory clientFactory = mock(McpClientFactory.class);
        McpClient client = mock(McpClient.class);

        when(clientFactory.create(McpTransportMode.STREAMABLE_HTTP)).thenReturn(client);
        when(client.invokeTool(any(), eq("search"), any())).thenReturn(new McpInvocationResult(200, "{\"status\":\"ok\"}"));
        when(secureCredentialStore.getSecretForUser(eq("system"), eq("binding.auth.ref"))).thenReturn("Bearer abc");
        when(secureCredentialStore.getSecretForUser(eq("system"), eq("tool.secret.ref"))).thenReturn("s3cr3t");

        McpToolCallbackFactory factory = newFactory(secureCredentialStore, clientFactory);

        McpBinding binding = new McpBinding(
                "b1", "Demo Binding", McpBindingStatus.ACTIVE, "https://mcp.example", McpTransportMode.STREAMABLE_HTTP,
                "binding.auth.ref", "demo", "artifact", "1.0", ArtifactStatus.SNAPSHOT, 1L, "", "hash", 1L, 1L);

        McpBindingTool tool = new McpBindingTool(
                "t1", "b1", "search", "search", "Search endpoint", true, false,
                List.of(new McpToolParameterConfig(
                        "apiToken", "string", true, "secret", "", McpToolParameterInputMode.SECRET, "tool.secret.ref"
                )),
                "schema");

        ToolCallback callback = factory.create(binding, tool);
        String result = callback.call("{}");

        assertEquals("{\"status\":\"ok\"}", result);
        verify(client).invokeTool(any(), eq("search"), eq(Map.of("apiToken", "s3cr3t")));
    }

        @Test
        void toolCallSuspendsWhenUserInputIsRequired() {
                SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
                McpClientFactory clientFactory = mock(McpClientFactory.class);
                McpToolCallbackFactory factory = newFactory(secureCredentialStore, clientFactory);

                McpBinding binding = new McpBinding(
                                "b1", "Demo Binding", McpBindingStatus.ACTIVE, "https://mcp.example", McpTransportMode.STREAMABLE_HTTP,
                                "", "demo", "artifact", "1.0", ArtifactStatus.SNAPSHOT, 1L, "", "hash", 1L, 1L);

                McpBindingTool tool = new McpBindingTool(
                                "t1", "b1", "approve", "approve", "Approval endpoint", true, false,
                                List.of(new McpToolParameterConfig(
                                                "approvalCode", "string", true, "Approval code", "", McpToolParameterInputMode.USER_ALWAYS_PROMPT, ""
                                )),
                                "schema");

                ToolCallback callback = factory.create(binding, tool);

                assertThrows(ToolSuspensionException.class, () -> callback.call("{}"));
        }

        @Test
        void toolCallSuspendsWhenToolRequiresAuthorization() {
                SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
                McpClientFactory clientFactory = mock(McpClientFactory.class);
                AuthorizationRuleEngine authorizationRuleEngine = new AuthorizationRuleEngine(Set.of());
                McpToolCallbackFactory factory = newFactory(secureCredentialStore, clientFactory, authorizationRuleEngine);

                McpBinding binding = new McpBinding(
                                "b1", "Demo Binding", McpBindingStatus.ACTIVE, "https://mcp.example", McpTransportMode.STREAMABLE_HTTP,
                                "", "demo", "artifact", "1.0", ArtifactStatus.SNAPSHOT, 1L, "", "hash", 1L, 1L);

                McpBindingTool tool = new McpBindingTool(
                                "t1", "b1", "search", "search", "Search endpoint", true, true,
                                List.of(),
                                "schema");

                ToolCallback callback = factory.create(binding, tool);

                assertThrows(ToolSuspensionException.class, () -> callback.call("{}"));
        }

        @Test
        void toolCallRunsAfterAllowOnceAuthorizationForRequiredTool() {
                SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
                McpClientFactory clientFactory = mock(McpClientFactory.class);
                McpClient client = mock(McpClient.class);
                AuthorizationRuleEngine authorizationRuleEngine = new AuthorizationRuleEngine(Set.of());
                McpToolCallbackFactory factory = newFactory(secureCredentialStore, clientFactory, authorizationRuleEngine);

                when(clientFactory.create(McpTransportMode.STREAMABLE_HTTP)).thenReturn(client);
                when(client.invokeTool(any(), eq("search"), any())).thenReturn(new McpInvocationResult(200, "{\"status\":\"ok\"}"));

                McpBinding binding = new McpBinding(
                                "b1", "Demo Binding", McpBindingStatus.ACTIVE, "https://mcp.example", McpTransportMode.STREAMABLE_HTTP,
                                "", "demo", "artifact", "1.0", ArtifactStatus.SNAPSHOT, 1L, "", "hash", 1L, 1L);

                McpBindingTool tool = new McpBindingTool(
                                "t1", "b1", "search", "search", "Search endpoint", true, true,
                                List.of(),
                                "schema");

                ToolCallback callback = factory.create(binding, tool);

                assertThrows(ToolSuspensionException.class, () -> callback.call("{}"));

                authorizationRuleEngine.addUseOnceRule("pending-id");
                String result = callback.call("{}");

                assertEquals("{\"status\":\"ok\"}", result);
                verify(client).invokeTool(any(), eq("search"), eq(Map.of()));
        }
}
