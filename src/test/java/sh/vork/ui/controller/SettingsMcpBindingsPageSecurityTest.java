package sh.vork.ui.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import sh.vork.ai.provider.AiModelService;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.oauth.OAuthClientService;
import sh.vork.security.UserManagementService;
import sh.vork.setup.SetupService;
import sh.vork.setup.SystemSettingsService;
import sh.vork.ui.SettingsPageRegistry;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(controllers = SettingsController.class)
@Import(SettingsMcpBindingsPageSecurityTest.MethodSecurityConfig.class)
class SettingsMcpBindingsPageSecurityTest {

    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettingsPageRegistry settingsPageRegistry;

    @MockitoBean
    private ToolRegistry toolRegistry;

    @MockitoBean
    private AiModelService aiModelService;

    @MockitoBean
    private SystemSettingsService systemSettingsService;

    @MockitoBean
    private OAuthClientService oauthClientService;

    @MockitoBean
    private SetupService setupService;

    @MockitoBean
    private UserManagementService userManagementService;

    @Test
    void mcpBindingsPage_forbiddenWithoutUsersManage() throws Exception {
        mockMvc.perform(get("/settings/mcp-bindings")
                        .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void mcpBindingsPage_allowedWithUsersManage() throws Exception {
        mockMvc.perform(get("/settings/mcp-bindings")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN", () -> "USERS_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/mcp-bindings"))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("name=\"_csrf_header\"")))
                .andExpect(content().string(containsString("id=\"mcp-catalog-tab-tools\"")))
                .andExpect(content().string(containsString("id=\"mcp-catalog-tab-resources\"")))
                .andExpect(content().string(containsString("id=\"mcp-catalog-tab-prompts\"")))
                .andExpect(content().string(containsString("id=\"mcp-resources-empty\"")))
                .andExpect(content().string(containsString("id=\"mcp-prompts-empty\"")));
    }
}
