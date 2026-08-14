package sh.vork.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import sh.vork.hub.repository.HubRepositoryRegistryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubCatalogServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void listCatalogItemsLoadsAndFiltersFromLocalRepository() throws Exception {
        Path repoRoot = tempDir.resolve("example-repo");
        Files.createDirectories(repoRoot.resolve("install/agents"));

        String hubIndex = """
                {
                  "repository": "Test",
                  "version": "1.0",
                  "components": [
                    {
                      "type": "agent",
                      "name": "Alpha Agent",
                      "artifactPath": "agents/demo/alpha/1.0/agent.json",
                      "installPath": "install/agents/alpha.json",
                      "logoPath": "agents/demo/alpha/1.0/logo.svg",
                      "docPath": "agents/demo/alpha/1.0/README.md"
                    },
                    {
                      "type": "job",
                      "name": "Beta Job",
                      "artifactPath": "jobs/demo/beta/1.0/job.json",
                      "installPath": "install/jobs/beta.json",
                      "logoPath": "jobs/demo/beta/1.0/logo.svg",
                      "docPath": "jobs/demo/beta/1.0/README.md"
                    }
                  ]
                }
                """;
        Files.writeString(repoRoot.resolve("hub-index.json"), hubIndex, StandardCharsets.UTF_8);
        Files.writeString(repoRoot.resolve("install/agents/alpha.json"), "{\"vorkAgentExport\":\"1.0\",\"agent\":{}}", StandardCharsets.UTF_8);

        MockEnvironment environment = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Example=file:///" + repoRoot.toString())
                .withProperty("vork.additionalRepositoriesFailFast", "true");

        HubRepositoryRegistryService registryService = new HubRepositoryRegistryService(environment);
        HubCatalogService service = new HubCatalogService(registryService, new ObjectMapper());

        List<HubCatalogService.HubCatalogItem> all = service.listCatalogItems("Example", "", "");
        assertEquals(2, all.size());

        List<HubCatalogService.HubCatalogItem> onlyAgent = service.listCatalogItems("Example", "agent", "alpha");
        assertEquals(1, onlyAgent.size());
        assertEquals("agent", onlyAgent.getFirst().type());
        assertEquals("/api/agents/import", onlyAgent.getFirst().installEndpoint());
    }

    @Test
    void prepareInstallPackageReturnsZipModeForSurface() throws Exception {
        Path repoRoot = tempDir.resolve("example-repo-surface");
        Files.createDirectories(repoRoot.resolve("install/surfaces"));

        byte[] zipBytes = "fake-zip-content".getBytes(StandardCharsets.UTF_8);
        Files.write(repoRoot.resolve("install/surfaces/surface-01.zip"), zipBytes);

        String hubIndex = """
                {
                  "repository": "Test",
                  "version": "1.0",
                  "components": [
                    {
                      "type": "surface",
                      "name": "Surface One",
                      "artifactPath": "surfaces/ux/surface01/1.0/surface.json",
                      "installPath": "install/surfaces/surface-01.zip"
                    }
                  ]
                }
                """;
        Files.writeString(repoRoot.resolve("hub-index.json"), hubIndex, StandardCharsets.UTF_8);

        MockEnvironment environment = new MockEnvironment()
                .withProperty("vork.additionalRepositories", "Example=file:///" + repoRoot.toString())
                .withProperty("vork.additionalRepositoriesFailFast", "true");

        HubRepositoryRegistryService registryService = new HubRepositoryRegistryService(environment);
        HubCatalogService service = new HubCatalogService(registryService, new ObjectMapper());

        HubCatalogService.InstallPackage pkg = service.prepareInstallPackage("Example", "surface", "install/surfaces/surface-01.zip");
        assertNotNull(pkg);
        assertEquals("surface", pkg.type());
        assertEquals("multipart-zip", pkg.installMode());
        assertEquals("/api/surfaces/import", pkg.installEndpoint());
        assertFalse(pkg.payloadBase64().isBlank());

        byte[] decoded = Base64.getDecoder().decode(pkg.payloadBase64());
        assertTrue(decoded.length > 0);
    }
}
