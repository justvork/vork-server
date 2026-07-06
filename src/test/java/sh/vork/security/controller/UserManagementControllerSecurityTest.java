package sh.vork.security.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import sh.vork.security.UserManagementService;
import sh.vork.security.VorkUser;
import sh.vork.setup.SetupService;

@WebMvcTest(controllers = UserManagementController.class)
@Import(UserManagementControllerSecurityTest.MethodSecurityConfig.class)
class UserManagementControllerSecurityTest {

    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserManagementService userManagementService;

    @MockBean
    private SetupService setupService;

    @Test
    void listUsers_forbiddenForUserWithoutUsersManage() throws Exception {
        mockMvc.perform(get("/api/users")
                        .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_allowedForAdminPermission() throws Exception {
        org.mockito.Mockito.when(userManagementService.listUsers()).thenReturn(List.of());

        mockMvc.perform(get("/api/users")
                        .with(user("admin").authorities(() -> "ROLE_ADMIN", () -> "USERS_MANAGE")))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_ADMIN", "USERS_MANAGE"})
    void createUser_allowedForUsersManageAuthority() throws Exception {
        org.mockito.Mockito.when(userManagementService.createUser("bob", "password123", "USER"))
            .thenReturn(new VorkUser("bob", "hash", "USER", true, 1L, 1L));

        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"password123\",\"role\":\"USER\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "alice", authorities = {"ROLE_USER"})
    void createUser_forbiddenWithoutUsersManageAuthority() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"password123\",\"role\":\"USER\"}"))
                .andExpect(status().isForbidden());
    }
}
