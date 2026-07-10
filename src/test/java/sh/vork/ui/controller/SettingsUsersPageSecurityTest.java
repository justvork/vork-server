package sh.vork.ui.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

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

@WebMvcTest(controllers = SettingsController.class)
@Import(SettingsUsersPageSecurityTest.MethodSecurityConfig.class)
class SettingsUsersPageSecurityTest {

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
    void usersPage_forbiddenWithoutUsersManage() throws Exception {
        mockMvc.perform(get("/settings/users")
                        .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersPage_allowedWithUsersManage() throws Exception {
        when(userManagementService.listUsers()).thenReturn(List.of(
                new UserManagementService.UserSummary("admin", "ADMIN", true, 1L, 1L)));

        mockMvc.perform(get("/settings/users")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN", () -> "USERS_MANAGE")))
                .andExpect(status().isOk())
                .andExpect(view().name("settings/users"))
                .andExpect(model().attributeExists("users"));
    }

    @Test
    void deleteOauthClient_forbiddenWithoutUsersManage() throws Exception {
        mockMvc.perform(post("/settings/oauth-clients/client-123/delete")
                        .with(csrf())
                        .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteOauthClient_allowedWithUsersManage() throws Exception {
        when(oauthClientService.deleteClientByUuidAsAdmin("client-123")).thenReturn(true);

        mockMvc.perform(post("/settings/oauth-clients/client-123/delete")
                        .with(csrf())
                        .with(user("admin").authorities(() -> "ROLE_ADMIN", () -> "USERS_MANAGE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("oauthClientDeleteMessage", "OAuth client deleted."));
    }
}
