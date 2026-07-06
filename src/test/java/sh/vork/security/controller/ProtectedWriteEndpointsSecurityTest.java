package sh.vork.security.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;

import sh.vork.ai.controller.AgentController;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.orm.DatabaseRepository;
import sh.vork.setup.SetupService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillCategoryService;
import sh.vork.skill.SkillController;
import sh.vork.skill.SkillService;
import sh.vork.typegen.FormToObjectConverter;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.controller.TypeDatabaseController;

@WebMvcTest(controllers = {
        AgentController.class,
        SkillController.class,
        TypeDatabaseController.class
})
@Import(ProtectedWriteEndpointsSecurityTest.MethodSecurityConfig.class)
class ProtectedWriteEndpointsSecurityTest {

    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DatabaseRepository<AgentTemplate> agentRepository;

    @MockBean
    private DatabaseRepository<Skill> skillRepository;

    @MockBean
    private SkillService skillService;

    @MockBean
    private SkillCategoryService skillCategoryService;

    @MockBean
    private TypeDatabaseService typeDatabaseService;

    @MockBean
    private FormToObjectConverter formToObjectConverter;

    @MockBean
    private JavaTypeClassLoader javaTypeClassLoader;

    @MockBean
    private DatabaseRepository<JavaType> javaTypeRepository;

    @MockBean
    private SetupService setupService;

    @Test
    void postAgents_forbiddenWithoutAgentsWrite() throws Exception {
        mockMvc.perform(post("/api/agents")
                .with(csrf())
                        .with(user("alice").authorities(() -> "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Demo\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postSkills_forbiddenWithoutSkillsWrite() throws Exception {
        mockMvc.perform(post("/api/skills")
                .with(csrf())
                        .with(user("alice").authorities(() -> "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Skill\",\"groupUuid\":\"g1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postTypes_forbiddenWithoutTypesWrite() throws Exception {
        mockMvc.perform(post("/api/types/sh.vork.generated.Product")
                .with(csrf())
                        .with(user("alice").authorities(() -> "ROLE_USER"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("uuid=1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postAgents_allowedWithAgentsWrite() throws Exception {
        mockMvc.perform(post("/api/agents")
                .with(csrf())
                        .with(user("admin").authorities(() -> "ROLE_ADMIN", () -> "AGENTS_WRITE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Demo\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void postSkills_allowedWithSkillsWrite() throws Exception {
        mockMvc.perform(post("/api/skills")
                .with(csrf())
                        .with(user("admin").authorities(() -> "ROLE_ADMIN", () -> "SKILLS_WRITE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Skill\",\"groupUuid\":\"g1\"}"))
                                .andExpect(status().isOk());
    }

    @Test
    void postTypes_allowedWithTypesWrite() throws Exception {
        mockMvc.perform(post("/api/types/sh.vork.generated.Product")
                .with(csrf())
                        .with(user("admin").authorities(() -> "ROLE_ADMIN", () -> "TYPES_WRITE"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("uuid=1"))
                .andExpect(status().isNotFound());
    }
}
