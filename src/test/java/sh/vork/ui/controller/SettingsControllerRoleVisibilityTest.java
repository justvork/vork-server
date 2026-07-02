package sh.vork.ui.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import sh.vork.ai.provider.AiModelService;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.oauth.OAuthClientService;
import sh.vork.setup.SystemSettingsService;
import sh.vork.security.UserManagementService;
import sh.vork.ui.OAuthClientsSettingsPage;
import sh.vork.ui.SettingsPage;
import sh.vork.ui.SettingsPageRegistry;
import sh.vork.ui.UsersSettingsPage;

class SettingsControllerRoleVisibilityTest {

    private SettingsPageRegistry registry;
    private SettingsController controller;

    @BeforeEach
    void setUp() {
        registry = mock(SettingsPageRegistry.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        AiModelService aiModelService = mock(AiModelService.class);
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        OAuthClientService oauthClientService = mock(OAuthClientService.class);
        UserManagementService userManagementService = mock(UserManagementService.class);

        controller = new SettingsController(
                registry,
                toolRegistry,
                aiModelService,
                systemSettingsService,
                oauthClientService,
                userManagementService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void settingsHome_hidesUsersTileForNonAdmin() {
        when(registry.getAllPages()).thenReturn(List.of(new UsersSettingsPage(), new OAuthClientsSettingsPage()));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "pw", "ROLE_USER"));

        Model model = new ExtendedModelMap();
        String view = controller.settingsHome(model);

        assertEquals("settings", view);
        @SuppressWarnings("unchecked")
        List<SettingsPage> pages = (List<SettingsPage>) model.getAttribute("pages");
        assertFalse(pages.stream().anyMatch(page -> "users".equals(page.getPath())));
        assertTrue(pages.stream().anyMatch(page -> "oauth-clients".equals(page.getPath())));
    }

    @Test
    void settingsHome_showsUsersTileForAdmin() {
        when(registry.getAllPages()).thenReturn(List.of(new UsersSettingsPage(), new OAuthClientsSettingsPage()));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", "pw", "ROLE_ADMIN", "USERS_MANAGE"));

        Model model = new ExtendedModelMap();
        controller.settingsHome(model);

        @SuppressWarnings("unchecked")
        List<SettingsPage> pages = (List<SettingsPage>) model.getAttribute("pages");
        assertTrue(pages.stream().anyMatch(page -> "users".equals(page.getPath())));
    }
}
