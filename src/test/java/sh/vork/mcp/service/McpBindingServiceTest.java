package sh.vork.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sh.vork.mcp.client.McpClient;
import sh.vork.mcp.client.McpClientConfig;
import sh.vork.mcp.client.McpClientFactory;
import sh.vork.mcp.client.dto.McpDiscoverResult;
import sh.vork.mcp.client.dto.McpDiscoveredPrompt;
import sh.vork.mcp.client.dto.McpDiscoveredResource;
import sh.vork.mcp.client.dto.McpDiscoveredTool;
import sh.vork.mcp.client.dto.McpDiscoveredToolParameter;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.model.McpBindingPrompt;
import sh.vork.mcp.model.McpBindingResource;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.model.McpBindingTool;
import sh.vork.mcp.model.McpToolParameterConfig;
import sh.vork.mcp.model.McpToolParameterInputMode;
import sh.vork.mcp.model.McpTransportMode;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.artifact.ArtifactStatus;
import sh.vork.security.SecureCredentialStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpBindingServiceTest {

    @Test
    void syncWithoutContractChangesPreservesToolOverrides() {
        DatabaseRepository<McpBinding> bindingRepo = new MapDatabaseRepository<>(McpBinding.class);
        DatabaseRepository<McpBindingTool> toolRepo = new MapDatabaseRepository<>(McpBindingTool.class);
        DatabaseRepository<McpBindingResource> resourceRepo = new MapDatabaseRepository<>(McpBindingResource.class);
        DatabaseRepository<McpBindingPrompt> promptRepo = new MapDatabaseRepository<>(McpBindingPrompt.class);

        McpClientFactory clientFactory = mock(McpClientFactory.class);
        McpClient client = mock(McpClient.class);
        SecureCredentialStore credentialStore = mock(SecureCredentialStore.class);

        McpBindingService service = new McpBindingService(
                bindingRepo,
                toolRepo,
                resourceRepo,
                promptRepo,
                clientFactory,
                new McpContractHashService(new ObjectMapper()),
                new McpContractDiffService(),
                credentialStore);

        McpBinding binding = binding("binding-1", McpBindingStatus.ACTIVE);
        bindingRepo.save(binding);

        String inputSchema = "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}";
        toolRepo.save(new McpBindingTool(
                "tool-uuid-1",
                binding.uuid(),
                "echo",
                "echo",
                "Echo",
                true,
                true,
                List.of(new McpToolParameterConfig(
                        "message",
                        "string",
                        true,
                        "message",
                        "custom-default",
                        McpToolParameterInputMode.USER_ALWAYS_PROMPT,
                        "")),
                sha256(inputSchema)));

        when(clientFactory.create(McpTransportMode.STREAMABLE_HTTP)).thenReturn(client);
        when(client.discover(any(McpClientConfig.class))).thenReturn(new McpDiscoverResult(
                List.of(new McpDiscoveredTool(
                        "echo",
                        "echo",
                        "Echo",
                        inputSchema,
                        List.of(new McpDiscoveredToolParameter("message", "string", true, "message", "")))),
                List.of(),
                List.of()));

        McpBindingService.SyncResult result = service.sync(binding.uuid());

        assertFalse(result.changed());

        List<McpBindingTool> tools = service.listTools(binding.uuid());
        assertEquals(1, tools.size());

        McpBindingTool persisted = tools.get(0);
        assertEquals("tool-uuid-1", persisted.uuid());
        assertTrue(persisted.requiresAuthorization());
        assertEquals(McpToolParameterInputMode.USER_ALWAYS_PROMPT, persisted.parameterConfigs().get(0).inputMode());
        assertEquals("custom-default", persisted.parameterConfigs().get(0).defaultValue());
    }

    @Test
    void syncWithContractChangesPreservesCompatibleParameterOverrides() {
        DatabaseRepository<McpBinding> bindingRepo = new MapDatabaseRepository<>(McpBinding.class);
        DatabaseRepository<McpBindingTool> toolRepo = new MapDatabaseRepository<>(McpBindingTool.class);
        DatabaseRepository<McpBindingResource> resourceRepo = new MapDatabaseRepository<>(McpBindingResource.class);
        DatabaseRepository<McpBindingPrompt> promptRepo = new MapDatabaseRepository<>(McpBindingPrompt.class);

        McpClientFactory clientFactory = mock(McpClientFactory.class);
        McpClient client = mock(McpClient.class);
        SecureCredentialStore credentialStore = mock(SecureCredentialStore.class);

        McpBindingService service = new McpBindingService(
                bindingRepo,
                toolRepo,
                resourceRepo,
                promptRepo,
                clientFactory,
                new McpContractHashService(new ObjectMapper()),
                new McpContractDiffService(),
                credentialStore);

        McpBinding binding = binding("binding-2", McpBindingStatus.ACTIVE);
        bindingRepo.save(binding);

        String oldSchema = "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}";
        toolRepo.save(new McpBindingTool(
                "tool-uuid-2",
                binding.uuid(),
                "echo",
                "echo",
                "Echo",
                true,
                true,
                List.of(
                        new McpToolParameterConfig("message", "string", true, "message", "", McpToolParameterInputMode.USER_ALWAYS_PROMPT, ""),
                        new McpToolParameterConfig("token", "string", false, "token", "", McpToolParameterInputMode.SECRET, "MCP_BINDING_TOKEN")
                ),
                sha256(oldSchema)));

        String newSchema = "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"},\"channel\":{\"type\":\"string\"}}}";

        when(clientFactory.create(McpTransportMode.STREAMABLE_HTTP)).thenReturn(client);
        when(client.discover(any(McpClientConfig.class))).thenReturn(new McpDiscoverResult(
                List.of(new McpDiscoveredTool(
                        "echo",
                        "echo",
                        "Echo",
                        newSchema,
                        List.of(
                                new McpDiscoveredToolParameter("message", "string", true, "message", ""),
                                new McpDiscoveredToolParameter("token", "string", false, "token", ""),
                                new McpDiscoveredToolParameter("channel", "string", false, "channel", "general")
                        ))),
                List.of(),
                List.of()));

        McpBindingService.SyncResult result = service.sync(binding.uuid());

        assertTrue(result.changed());

        List<McpBindingTool> tools = service.listTools(binding.uuid());
        assertEquals(1, tools.size());
        McpBindingTool persisted = tools.get(0);

        assertEquals("tool-uuid-2", persisted.uuid());
        assertTrue(persisted.requiresAuthorization());

        McpToolParameterConfig message = parameterByName(persisted, "message");
        McpToolParameterConfig token = parameterByName(persisted, "token");
        McpToolParameterConfig channel = parameterByName(persisted, "channel");

        assertEquals(McpToolParameterInputMode.USER_ALWAYS_PROMPT, message.inputMode());
        assertEquals(McpToolParameterInputMode.SECRET, token.inputMode());
        assertEquals("MCP_BINDING_TOKEN", token.bindingSecretRef());
        assertEquals(McpToolParameterInputMode.AI_OPTIONAL, channel.inputMode());
        assertEquals("general", channel.defaultValue());
    }

    @Test
    void refreshDriftStatusClearsErrorWhenEndpointIsReachable() {
        DatabaseRepository<McpBinding> bindingRepo = new MapDatabaseRepository<>(McpBinding.class);
        DatabaseRepository<McpBindingTool> toolRepo = new MapDatabaseRepository<>(McpBindingTool.class);
        DatabaseRepository<McpBindingResource> resourceRepo = new MapDatabaseRepository<>(McpBindingResource.class);
        DatabaseRepository<McpBindingPrompt> promptRepo = new MapDatabaseRepository<>(McpBindingPrompt.class);

        McpClientFactory clientFactory = mock(McpClientFactory.class);
        McpClient client = mock(McpClient.class);
        SecureCredentialStore credentialStore = mock(SecureCredentialStore.class);

        McpBindingService service = new McpBindingService(
                bindingRepo,
                toolRepo,
                resourceRepo,
                promptRepo,
                clientFactory,
                new McpContractHashService(new ObjectMapper()),
                new McpContractDiffService(),
                credentialStore);

        McpBinding errorBinding = binding("binding-3", McpBindingStatus.ERROR);
        bindingRepo.save(errorBinding);

        String schema = "{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\"}}}";
        toolRepo.save(new McpBindingTool(
                "tool-uuid-3",
                errorBinding.uuid(),
                "echo",
                "echo",
                "Echo",
                true,
                false,
                List.of(new McpToolParameterConfig("message", "string", true, "", "", McpToolParameterInputMode.AI_REQUIRED, "")),
                sha256(schema)));

        when(clientFactory.create(McpTransportMode.STREAMABLE_HTTP)).thenReturn(client);
        when(client.discover(any(McpClientConfig.class))).thenReturn(new McpDiscoverResult(
                List.of(new McpDiscoveredTool(
                        "echo",
                        "echo",
                        "Echo",
                        schema,
                        List.of(new McpDiscoveredToolParameter("message", "string", true, "", "")))),
                List.of(),
                List.of()));

        McpBinding refreshed = service.refreshDriftStatus(errorBinding.uuid());

        assertEquals(McpBindingStatus.INACTIVE, refreshed.status());
        assertEquals("", refreshed.lastDiscoveryError());
    }

    private static McpToolParameterConfig parameterByName(McpBindingTool tool, String name) {
                return tool.parameterConfigs().stream()
                .filter(p -> name.equals(p.name()))
                .findFirst()
                .orElseThrow();
    }

    private static McpBinding binding(String uuid, McpBindingStatus status) {
        return new McpBinding(
                uuid,
                "Everything",
                status,
                "https://mcp.example",
                McpTransportMode.STREAMABLE_HTTP,
                "",
                "demo",
                "artifact",
                "1.0",
                ArtifactStatus.SNAPSHOT,
                0L,
                "",
                "hash",
                1L,
                2L);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
