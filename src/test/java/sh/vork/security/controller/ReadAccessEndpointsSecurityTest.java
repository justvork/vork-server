package sh.vork.security.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.controller.AgentController;
import sh.vork.orm.DatabaseRepository;
import sh.vork.setup.SetupService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillCategoryService;
import sh.vork.skill.SkillController;
import sh.vork.skill.SkillService;
import sh.vork.skill.SkillVisibility;

@WebMvcTest(controllers = {
        AgentController.class,
        SkillController.class
})
@Import(ReadAccessEndpointsSecurityTest.MethodSecurityConfig.class)
class ReadAccessEndpointsSecurityTest {

    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DatabaseRepository<AgentTemplate> agentRepository;

    @MockitoBean
    private DatabaseRepository<Skill> skillRepository;

    @MockitoBean
    private SkillService skillService;

    @MockitoBean
    private SkillCategoryService skillCategoryService;

        @MockitoBean
        private SetupService setupService;

    @BeforeEach
    void setUp() {
        Mockito.when(agentRepository.list(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(invocation -> Stream.of(new AgentTemplate(
                        "a1",
                        "General Agent",
                        "prompt",
                        List.of(),
                        false,
                        List.of(),
                        AgentType.INTERACTIVE)));
        Mockito.when(skillRepository.list(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(invocation -> Stream.of());
        Mockito.when(skillService.list())
                .thenReturn(List.of(new Skill(
                        "s1",
                        "List Files",
                        "demo",
                        "g1",
                        SkillVisibility.PUBLIC,
                        List.of(),
                        "instr",
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        1L,
                        1L,
                        List.of())));
    }

    @Test
    void userCanListAgents() throws Exception {
        mockMvc.perform(get("/api/agents")
                        .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isOk());
    }

    @Test
    void userCanListSkills() throws Exception {
        mockMvc.perform(get("/api/skills")
                        .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isOk());
    }
}
