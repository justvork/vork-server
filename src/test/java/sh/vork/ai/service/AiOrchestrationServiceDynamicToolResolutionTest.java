package sh.vork.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.session.SessionToolStore;
import sh.vork.mcp.runtime.McpRuntimeToolService;
import sh.vork.orm.DatabaseRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiOrchestrationServiceDynamicToolResolutionTest {

    @Test
    void resolveDynamicToolCallbackForSession_resolvesMcpRuntimeTool() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentTemplateRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);

        SessionToolStore sessionToolStore = mock(SessionToolStore.class);
        sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory = mock(sh.vork.skill.SkillToolCallbackFactory.class);
        McpRuntimeToolService mcpRuntimeToolService = mock(McpRuntimeToolService.class);

        AiOrchestrationService service = new AiOrchestrationService(
                Map.<AiProvider, ChatClient>of(),
                null,
                mock(SessionEnvironmentService.class),
                sessionRepo,
                agentTemplateRepo,
                skillRepo,
                Map.of(),
                sessionToolStore,
                skillToolCallbackFactory,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        ReflectionTestUtils.setField(service, "mcpRuntimeToolService", mcpRuntimeToolService);

        String sessionUuid = "session-mcp-runtime";
        String bindingUuid = "binding-1";
        String toolName = "mcp_everything__echo";

        AiSession session = new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "admin",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of("SESSION_MCP_BINDING_UUIDS", bindingUuid),
                null,
                null,
                null,
                null,
                null,
                null);

        ToolCallback mcpCallback = new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name(toolName)
                    .description("MCP echo")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{\"status\":\"ok\"}";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };

        when(sessionRepo.get(sessionUuid)).thenReturn(session);
        when(sessionToolStore.getTools(sessionUuid)).thenReturn(List.of());
        when(mcpRuntimeToolService.listToolCallbacksForBindings(List.of(bindingUuid))).thenReturn(List.of(mcpCallback));

        ToolCallback resolved = service.resolveDynamicToolCallbackForSession(sessionUuid, toolName);

        assertSame(mcpCallback, resolved);
    }

    @Test
    void resolveDynamicToolCallbackForSession_returnsNullWhenNoMcpMatch() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        AiOrchestrationService service = new AiOrchestrationService(
                Map.<AiProvider, ChatClient>of(),
                null,
                null,
                sessionRepo,
                mock(DatabaseRepository.class),
                mock(DatabaseRepository.class),
                Map.of(),
                mock(SessionToolStore.class),
                mock(sh.vork.skill.SkillToolCallbackFactory.class),
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        when(sessionRepo.get("missing")).thenReturn(null);

        assertNull(service.resolveDynamicToolCallbackForSession("missing", "mcp_any__tool"));
    }
}
