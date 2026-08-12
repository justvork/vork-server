package sh.vork.security.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.controller.ChatController;
import sh.vork.ai.controller.AgentController;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.provider.AiModelService;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.AgentAssignmentService;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.binding.BindingCatalogService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.ReflectionAuthenticationMode;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionService;
import sh.vork.reflection.ReflectionType;
import sh.vork.scheduling.domain.ScheduledJob;
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
        ChatController.class,
        TypeDatabaseController.class
})
@Import(ProtectedWriteEndpointsSecurityTest.MethodSecurityConfig.class)
class ProtectedWriteEndpointsSecurityTest {

    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DatabaseRepository<AgentTemplate> agentRepository;

    @MockitoBean
    private DatabaseRepository<Skill> skillRepository;

        @MockitoBean
        private DatabaseRepository<ScheduledJob> jobRepository;

    @MockitoBean
    private SkillService skillService;

    @MockitoBean
    private SkillCategoryService skillCategoryService;

        @MockitoBean
        private AgentAssignmentService agentAssignmentService;

        @MockitoBean
        private ReflectionService reflectionService;

        @MockitoBean
        private ChatService chatService;

        @MockitoBean
        private AiOrchestrationService aiOrchestrationService;

        @MockitoBean
        private TerminalStreamRouter terminalStreamRouter;

        @MockitoBean
        private AiModelService aiModelService;

        @MockitoBean
        private ToolRegistry toolRegistry;

        @MockitoBean
        private SessionEnvironmentService sessionEnvironmentService;

        @MockitoBean
        private SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    private TypeDatabaseService typeDatabaseService;

    @MockitoBean
    private FormToObjectConverter formToObjectConverter;

    @MockitoBean
    private JavaTypeClassLoader javaTypeClassLoader;

    @MockitoBean
    private DatabaseRepository<JavaType> javaTypeRepository;

    @MockitoBean
    private SetupService setupService;

        @MockitoBean
        private BindingCatalogService bindingCatalogService;

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
                        .content("{\"name\":\"Demo\",\"groupId\":\"vork\",\"artifactId\":\"demoAgent\"}"))
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

    @Test
    void sessionExtrasMutations_forbiddenForRoleUser() throws Exception {
        String sessionUuid = "s1";
        String skillUuid = "skill-1";
        String toolId = "tool-1";
        String bindingUuid = "binding-1";

        mockMvc.perform(post("/api/chat/session/{sessionUuid}/session-skills/{skillUuid}", sessionUuid, skillUuid)
                .with(csrf())
                .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/chat/session/{sessionUuid}/session-skills/{skillUuid}", sessionUuid, skillUuid)
                .with(csrf())
                .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/chat/session/{sessionUuid}/session-tools/{toolId}", sessionUuid, toolId)
                .with(csrf())
                .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/chat/session/{sessionUuid}/session-tools/{toolId}", sessionUuid, toolId)
                .with(csrf())
                .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/chat/session/{sessionUuid}/session-reflection-bindings/{bindingUuid}", sessionUuid, bindingUuid)
                .with(csrf())
                .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/chat/session/{sessionUuid}/session-reflection-bindings/{bindingUuid}", sessionUuid, bindingUuid)
                .with(csrf())
                .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentConfig_includesAgentReflections_forRoleUser() throws Exception {
        AiSession session = new AiSession(
                "s1",
                "GEMINI",
                SessionOriginMode.WEB,
                "alice",
                "Chat",
                1L,
                0,
                List.of(),
                Map.of(),
                AiSessionStatus.RUNNING,
                "a1",
                null,
                List.of(),
                List.of(),
                List.of());
        AgentTemplate agentTemplate = new AgentTemplate(
                "a1",
                "Demo Agent",
                "prompt",
                List.of(),
                false,
                List.of(),
                sh.vork.ai.agent.AgentType.INTERACTIVE,
                List.of("b1"),
                List.of());
        ReflectionBinding binding = new ReflectionBinding("b1", "g1", "Binding One", "", Map.of(), 1L, 1L, 1L);
        ReflectionGroup group = new ReflectionGroup(
                "g1",
                "groupone",
                "Group One",
                "",
                ReflectionType.REST,
                "",
                true,
                List.of(),
                List.of(),
                ReflectionAuthenticationMode.NONE,
                "",
                1L,
                1L,
                1L);

        Mockito.when(chatService.getSessionForCurrentUser("s1")).thenReturn(session);
        Mockito.when(chatService.listAgentTemplates()).thenReturn(List.of(agentTemplate));
        Mockito.when(chatService.getSessionReflectionBindingUuids(session)).thenReturn(List.of());
        Mockito.when(reflectionService.getBindingByUuid("b1")).thenReturn(binding);
        Mockito.when(reflectionService.getGroup("g1")).thenReturn(group);
        Mockito.when(toolRegistry.getAvailableTools()).thenReturn(List.of());

        mockMvc.perform(get("/api/chat/session/s1/agent-config")
                .with(user("alice").authorities(() -> "ROLE_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentReflectionBindings[0].uuid").value("b1"))
                .andExpect(jsonPath("$.agentReflectionBindings[0].groupName").value("Group One"))
                .andExpect(jsonPath("$.sessionReflectionBindings").isArray());
    }
}
