package sh.vork.hub.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class HubRepositoryControllerTest {

    @Test
    void listRepositoriesReturnsResolvedSources() {
        HubRepositoryRegistryService service = mock(HubRepositoryRegistryService.class);
        when(service.resolveRepositories()).thenReturn(List.of(
                new HubRepositoryDefinition("Production", URI.create("https://raw.githubusercontent.com/justvork/vork-central/main"), false, true, "Built-in repository"),
                new HubRepositoryDefinition("Examples", URI.create("https://example.com/repositories/examples"), true, false, "Repository unreachable during startup probe")));

        HubRepositoryController controller = new HubRepositoryController(service);

        ResponseEntity<?> response = controller.listRepositories();

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<HubRepositoryController.HubRepositorySummary> body =
                assertInstanceOf(List.class, response.getBody());
        assertEquals(2, body.size());
        assertEquals("Production", body.get(0).name());
        assertEquals("https://example.com/repositories/examples", body.get(1).baseUrl());
        assertTrue(body.get(0).available());
    }

    @Test
    void listRepositoriesReturns502WhenResolutionFails() {
        HubRepositoryRegistryService service = mock(HubRepositoryRegistryService.class);
        when(service.resolveRepositories()).thenThrow(new IllegalStateException("Invalid repository entry"));

        HubRepositoryController controller = new HubRepositoryController(service);
        ResponseEntity<?> response = controller.listRepositories();

        assertEquals(502, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
        assertTrue(String.valueOf(body.get("detail")).contains("Invalid repository entry"));
    }
}
