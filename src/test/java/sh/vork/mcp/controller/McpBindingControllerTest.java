package sh.vork.mcp.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import sh.vork.mcp.client.dto.McpDiscoverResult;
import sh.vork.mcp.controller.dto.McpBindingUpsertRequest;
import sh.vork.mcp.controller.dto.McpToolUpdateRequest;
import sh.vork.mcp.model.McpBindingTool;
import sh.vork.mcp.model.McpBindingPrompt;
import sh.vork.mcp.model.McpBindingResource;
import sh.vork.mcp.model.McpToolParameterConfig;
import sh.vork.mcp.model.McpToolParameterInputMode;
import sh.vork.mcp.service.McpBindingService;
import sh.vork.mcp.service.McpContractDiffService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpBindingControllerTest {

    @Test
    void driftUsesReadOnlyInspectionResult() {
        McpBindingService service = mock(McpBindingService.class);
        McpBindingController controller = new McpBindingController(service);

        McpContractDiffService.McpContractDiffSection section =
                new McpContractDiffService.McpContractDiffSection(List.of("added"), List.of(), List.of());
        McpContractDiffService.McpContractDiff diff =
                new McpContractDiffService.McpContractDiff(section, section, section);

        when(service.inspectDrift("b-1"))
                .thenReturn(new McpBindingService.DriftInspection("b-1", "sha256:old", "sha256:new", 123L, diff));

        ResponseEntity<?> response = controller.drift("b-1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        verify(service).inspectDrift("b-1");
    }

    @SuppressWarnings("unchecked")
    @Test
    void syncReturnsStructuredResult() {
        McpBindingService service = mock(McpBindingService.class);
        McpBindingController controller = new McpBindingController(service);

        McpContractDiffService.McpContractDiffSection section =
                new McpContractDiffService.McpContractDiffSection(List.of("added"), List.of(), List.of());
        McpContractDiffService.McpContractDiff diff =
                new McpContractDiffService.McpContractDiff(section, section, section);

        McpBindingService.DiscoverySnapshot snapshot = new McpBindingService.DiscoverySnapshot(
                new McpDiscoverResult(List.of(), List.of(), List.of()),
                "sha256:new",
                123L);

        when(service.sync("b-1"))
                .thenReturn(new McpBindingService.SyncResult(
                        snapshot,
                        diff,
                        true,
                        sh.vork.mcp.model.McpBindingStatus.INACTIVE));

        ResponseEntity<?> response = controller.sync("b-1");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("changed"));
        assertEquals("INACTIVE", body.get("statusAfterSync"));
        verify(service).sync("b-1");
    }

        @Test
        void updateToolDelegatesToService() {
                McpBindingService service = mock(McpBindingService.class);
                McpBindingController controller = new McpBindingController(service);

                McpToolUpdateRequest request = new McpToolUpdateRequest(
                                true,
                                true,
                                List.of(new McpToolUpdateRequest.ParameterConfig(
                                                "token",
                                                McpToolParameterInputMode.SECRET,
                                                "",
                                                "new-secret")));

                McpBindingTool updated = new McpBindingTool(
                                "tool-uuid",
                                "binding-uuid",
                                "search",
                                "search",
                                "desc",
                                true,
                                true,
                                List.of(new McpToolParameterConfig("token", "string", true, "", "", McpToolParameterInputMode.SECRET, "ref")),
                                "schema");

                when(service.updateToolConfig("b-1", "search", request, "system")).thenReturn(updated);

                ResponseEntity<?> response = controller.updateTool("b-1", "search", request);

                assertEquals(200, response.getStatusCode().value());
                assertEquals(updated, response.getBody());
                verify(service).updateToolConfig("b-1", "search", request, "system");
        }

        @Test
        void listResourcesReturnsServiceResult() {
                McpBindingService service = mock(McpBindingService.class);
                McpBindingController controller = new McpBindingController(service);

                List<McpBindingResource> resources = List.of(new McpBindingResource(
                                "r-1", "b-1", "res://catalog", "Catalog", "Metadata", "res://catalog/{id}", "{}", "h1"));
                when(service.listResources("b-1")).thenReturn(resources);

                ResponseEntity<?> response = controller.listResources("b-1");

                assertEquals(200, response.getStatusCode().value());
                assertEquals(resources, response.getBody());
                verify(service).listResources("b-1");
        }

        @Test
        void listPromptsReturnsServiceResult() {
                McpBindingService service = mock(McpBindingService.class);
                McpBindingController controller = new McpBindingController(service);

                List<McpBindingPrompt> prompts = List.of(new McpBindingPrompt(
                                "p-1", "b-1", "prompt://summarize", "Summarize", "Summary prompt", "{}", "h2"));
                when(service.listPrompts("b-1")).thenReturn(prompts);

                ResponseEntity<?> response = controller.listPrompts("b-1");

                assertEquals(200, response.getStatusCode().value());
                assertEquals(prompts, response.getBody());
                verify(service).listPrompts("b-1");
        }

        @SuppressWarnings("unchecked")
        @Test
        void createReturns502WhenUpstreamMcpFails() {
                McpBindingService service = mock(McpBindingService.class);
                McpBindingController controller = new McpBindingController(service);

                McpBindingUpsertRequest request = new McpBindingUpsertRequest(
                                "everything",
                                "http://localhost:3001",
                                sh.vork.mcp.model.McpTransportMode.STREAMABLE_HTTP,
                                "",
                                "",
                                "",
                                "SNAPSHOT",
                                sh.vork.reflection.ArtifactStatus.SNAPSHOT);

                when(service.createOrUpdate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                                .thenThrow(new IllegalStateException("MCP endpoint not found"));

                ResponseEntity<?> response = controller.create(request);

                assertEquals(502, response.getStatusCode().value());
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertEquals("error", body.get("status"));
                assertEquals("MCP_UPSTREAM_FAILED", body.get("code"));
        }
}
