package sh.vork.oauth;

import sh.vork.artifact.ArtifactStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sh.vork.hub.repository.HubRepositoryDefinition;
import sh.vork.hub.repository.HubRepositoryRegistryService;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.mock.MapDatabaseRepository;

@ExtendWith(MockitoExtension.class)
class OAuthTemplateServiceTest {

    @Mock
    private RepositoryFactory repositoryFactory;

        @Mock
        private HubRepositoryRegistryService hubRepositoryRegistryService;

    private OAuthTemplateService service;

    @BeforeEach
    void setUp() {
        MapDatabaseRepository<OAuthTemplateEntity> templateRepository = new MapDatabaseRepository<>(OAuthTemplateEntity.class);
        when(repositoryFactory.create(OAuthTemplateEntity.class)).thenReturn(templateRepository);
        service = new OAuthTemplateService(repositoryFactory);
    }

        @Test
        void resolveSyncRootUsesProductionRepositoryFromHubRegistry() {
                OAuthTemplateService serviceWithRegistry = new OAuthTemplateService(repositoryFactory, hubRepositoryRegistryService);
                when(hubRepositoryRegistryService.resolveRepositories()).thenReturn(List.of(
                                new HubRepositoryDefinition("Staging", URI.create("https://raw.githubusercontent.com/justvork/vork-central/staging"), false, true, "ok"),
                                new HubRepositoryDefinition("Production", URI.create("https://raw.githubusercontent.com/example/custom-central/main/nested/path"), true, true, "ok")));

                URI root = serviceWithRegistry.resolveSyncRoot();

                assertEquals(URI.create("https://raw.githubusercontent.com/example/custom-central/main/nested/path"), root);
        }

        @Test
        void resolveSyncRootFallsBackToDefaultWhenHubRegistryFails() {
                OAuthTemplateService serviceWithRegistry = new OAuthTemplateService(repositoryFactory, hubRepositoryRegistryService);
                when(hubRepositoryRegistryService.resolveRepositories()).thenThrow(new IllegalStateException("boom"));

                URI root = serviceWithRegistry.resolveSyncRoot();

                assertEquals(URI.create("https://raw.githubusercontent.com/justvork/vork-central/main"), root);
        }

        @Test
        void resolveSyncRootUsesPreferredRepositoryNameWhenProvided() {
                OAuthTemplateService serviceWithRegistry = new OAuthTemplateService(repositoryFactory, hubRepositoryRegistryService);
                when(hubRepositoryRegistryService.resolveRepositories()).thenReturn(List.of(
                                new HubRepositoryDefinition("Production", URI.create("https://raw.githubusercontent.com/justvork/vork-central/main"), false, true, "ok"),
                                new HubRepositoryDefinition("Examples", URI.create("file:///tmp/vork-examples"), true, true, "ok")));

                URI root = serviceWithRegistry.resolveSyncRoot("Examples");

                assertEquals(URI.create("file:///tmp/vork-examples"), root);
        }

    @Test
    void createAndGetTemplateRoundTrip() {
        OAuthTemplate created = service.createTemplate(new OAuthTemplate(
                null,
                "Google OAuth",
                "google_oauth",
                "Google Workspace defaults",
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create("https://oauth2.googleapis.com/token"),
                List.of("openid", "email"),
                Map.of("access_type", "offline", "prompt", "consent"),
                ArtifactStatus.SNAPSHOT));

        assertEquals("oauth-google_oauth-SNAPSHOT", created.id());
        assertEquals("Google OAuth", created.name());
        assertEquals("google_oauth", created.clientName());
        assertEquals(2, created.scopes().size());

        OAuthTemplate fetched = service.getTemplate(created.id());
        assertNotNull(fetched);
        assertEquals(created.id(), fetched.id());
        assertEquals("https://oauth2.googleapis.com/token", fetched.tokenEndpoint().toString());
        assertEquals("offline", fetched.authorizationParameters().get("access_type"));
    }

    @Test
    void updateTemplateReplacesValues() {
        OAuthTemplate created = service.createTemplate(new OAuthTemplate(
                null,
                "GitHub OAuth",
                "github_oauth",
                "Initial",
                URI.create("https://github.com/login/oauth/authorize"),
                URI.create("https://github.com/login/oauth/access_token"),
                List.of("repo"),
                Map.of("allow_signup", "false"),
                ArtifactStatus.SNAPSHOT));

        OAuthTemplate updated = service.updateTemplate(created.id(), new OAuthTemplate(
                created.id(),
                "GitHub OAuth Updated",
                "github_oauth",
                "Updated description",
                URI.create("https://github.com/login/oauth/authorize"),
                URI.create("https://github.com/login/oauth/access_token"),
                List.of("repo", "read:user"),
                Map.of("allow_signup", "true"),
                ArtifactStatus.SNAPSHOT));

        assertNotNull(updated);
        assertEquals(created.id(), updated.id());
        assertEquals("GitHub OAuth Updated", updated.name());
        assertEquals("github_oauth", updated.clientName());
        assertEquals(2, updated.scopes().size());
        assertEquals("true", updated.authorizationParameters().get("allow_signup"));
    }

    @Test
    void deleteTemplateRemovesEntity() {
        OAuthTemplate created = service.createTemplate(new OAuthTemplate(
                null,
                "Xero OAuth",
                "xero_oauth",
                "Accounting",
                URI.create("https://login.xero.com/identity/connect/authorize"),
                URI.create("https://identity.xero.com/connect/token"),
                List.of("openid"),
                Map.of(),
                ArtifactStatus.SNAPSHOT));

        boolean deleted = service.deleteTemplate(created.id());
        assertTrue(deleted);

        OAuthTemplate fetched = service.getTemplate(created.id());
        assertNull(fetched);

        boolean deletedAgain = service.deleteTemplate(created.id());
        assertFalse(deletedAgain);
    }

    @Test
        void exportTemplateProducesPackageWithSingleTemplate() {
                OAuthTemplate created = service.createTemplate(new OAuthTemplate(
                null,
                "Google OAuth",
                "google_oauth",
                "Google template",
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create("https://oauth2.googleapis.com/token"),
                List.of("openid", "email"),
                Map.of("access_type", "offline"),
                ArtifactStatus.SNAPSHOT));

                OAuthTemplateService.OAuthTemplateExportPackage pkg = service.exportTemplate(created.id());

        assertEquals("vorkOAuthTemplateExport", pkg.vorkOAuthTemplateExport());
        assertEquals(1, pkg.version());
        assertEquals(1, pkg.templates().size());
        assertEquals("Google OAuth", pkg.templates().get(0).name());
    }

        @Test
        void exportTemplateReturnsNullWhenTemplateMissing() {
                        OAuthTemplateService.OAuthTemplateExportPackage pkg = service.exportTemplate("oauth-missing-SNAPSHOT");
                assertNull(pkg);
        }

    @Test
            void importTemplatesConvertsUuidToVidAndUpdatesByDeterministicId() {
                String legacyUuid = "8c5f2094-3103-60d9-a8e4-6c22de802020";

        OAuthTemplateService.OAuthTemplateImportResult first = service.importTemplates(
                new OAuthTemplateService.OAuthTemplateExportPackage(
                        "vorkOAuthTemplateExport",
                        1,
                        List.of(new OAuthTemplate(
                                        legacyUuid,
                                "Shared Template",
                                "shared_template",
                                "First",
                                URI.create("https://example.com/auth"),
                                URI.create("https://example.com/token"),
                                List.of("scope1"),
                                Map.of("prompt", "consent"),
                                ArtifactStatus.SNAPSHOT))));

        assertEquals("ok", first.status());
        assertEquals(1, first.created());
        assertEquals(0, first.updated());

        OAuthTemplateService.OAuthTemplateImportResult second = service.importTemplates(
                new OAuthTemplateService.OAuthTemplateExportPackage(
                        "vorkOAuthTemplateExport",
                        1,
                        List.of(new OAuthTemplate(
                                legacyUuid,
                                "Shared Template Updated",
                                "shared_template",
                                "Second",
                                URI.create("https://example.com/auth"),
                                URI.create("https://example.com/token"),
                                List.of("scope1", "scope2"),
                                Map.of("prompt", "login"),
                                ArtifactStatus.SNAPSHOT))));

        assertEquals("ok", second.status());
        assertEquals(0, second.created());
        assertEquals(1, second.updated());

        OAuthTemplate saved = service.getTemplate("oauth-shared_template-SNAPSHOT");
        assertNotNull(saved);
        assertEquals("oauth-shared_template-SNAPSHOT", saved.id());
        assertEquals("Shared Template Updated", saved.name());
                assertEquals("shared_template", saved.clientName());
        assertEquals(2, saved.scopes().size());
        assertEquals("login", saved.authorizationParameters().get("prompt"));
    }

        @Test
        void createTemplateDerivesClientNameWhenMissing() {
                OAuthTemplate created = service.createTemplate(new OAuthTemplate(
                                null,
                                "Google Calendar Read Only",
                                null,
                                "Google Calendar scope",
                                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                                URI.create("https://oauth2.googleapis.com/token"),
                                List.of("https://www.googleapis.com/auth/calendar.readonly"),
                                Map.of(),
                                ArtifactStatus.SNAPSHOT));

                assertEquals("google_calendar_read_only", created.clientName());
        }

        @Test
        void createTemplateRejectsDuplicateClientName() {
                service.createTemplate(new OAuthTemplate(
                                null,
                                "Template A",
                                "duplicate_client",
                                "A",
                                URI.create("https://example.com/auth"),
                                URI.create("https://example.com/token"),
                                List.of("scope"),
                                Map.of(),
                                ArtifactStatus.SNAPSHOT));

                IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalArgumentException.class,
                                () -> service.createTemplate(new OAuthTemplate(
                                                null,
                                                "Template B",
                                                "duplicate_client",
                                                "B",
                                                URI.create("https://example.com/auth"),
                                                URI.create("https://example.com/token"),
                                                List.of("scope"),
                                                Map.of(),
                                                ArtifactStatus.SNAPSHOT)));

                assertTrue(error.getMessage().contains("deterministic VID"));
        }
}
