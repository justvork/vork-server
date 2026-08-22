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
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.session.SessionToolStore;
import sh.vork.mcp.runtime.McpRuntimeToolService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionService;
import sh.vork.reflection.ReflectionToolCallbackFactory;
import sh.vork.reflection.ReflectionType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiOrchestrationServiceDynamicToolResolutionTest {

    @Test
    void resolveDynamicToolCallbackForSession_resolvesReflectionToolFromLegacyBindingName() {
    @SuppressWarnings("unchecked")
    DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
    @SuppressWarnings("unchecked")
    DatabaseRepository<AgentTemplate> agentTemplateRepo = mock(DatabaseRepository.class);
    @SuppressWarnings("unchecked")
    DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);

    SessionToolStore sessionToolStore = mock(SessionToolStore.class);
    sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory = mock(sh.vork.skill.SkillToolCallbackFactory.class);
    ReflectionService reflectionService = mock(ReflectionService.class);
    ReflectionToolCallbackFactory reflectionToolCallbackFactory = mock(ReflectionToolCallbackFactory.class);

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
        null,
        null,
        null);

    ReflectionTestUtils.setField(service, "reflectionService", reflectionService);
    ReflectionTestUtils.setField(service, "reflectionToolCallbackFactory", reflectionToolCallbackFactory);

    String sessionUuid = "session-reflection-legacy-name";
    String agentId = "agent-tpl-concierge-001";
    String groupUuid = "group-calendar";
    String bindingUuid = "binding-calendar";
    String bindingName = "Google Calendar";
    String reflectionToolName = "calendarLookup";

    AiSession session = new AiSession(
        sessionUuid,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "admin",
        "Untitled",
        System.currentTimeMillis(),
        0,
        List.of(),
        Map.of(),
        null,
        agentId,
        null,
        null,
        null,
        null);

    AgentTemplate template = new AgentTemplate(
        agentId,
        "Concierge",
        "",
        List.of("listFiles"),
        true,
        List.of(),
        AgentType.INTERACTIVE,
        List.of(bindingName));

    Reflection reflection = new Reflection(
        "reflection-uuid",
        reflectionToolName,
        "Calendar Lookup",
        "",
        groupUuid,
        List.of(),
        "GET",
        "https://example.invalid/calendar",
        Map.of(),
        Map.of(),
        "",
        "application/json",
        "application/json",
        "{}",
        1L,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ReflectionBinding binding = new ReflectionBinding(
        bindingUuid,
        reflection.uuid(),
        bindingName,
        "https://example.invalid",
        Map.of(),
        1L,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ReflectionGroup group = new ReflectionGroup(
        groupUuid,
        "calendar",
        "Calendar",
        "",
        ReflectionType.REST,
        "https://example.invalid",
        true,
        List.of(),
        List.of(),
        null,
        null,
        "legacy",
        "reflectiongroup",
        "SNAPSHOT",
        null,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ToolCallback reflectionCallback = new ToolCallback() {
        private final ToolDefinition definition = DefaultToolDefinition.builder()
            .name(reflectionToolName)
            .description("calendar")
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
    when(agentTemplateRepo.get(agentId)).thenReturn(template);
    when(sessionToolStore.getTools(sessionUuid)).thenReturn(List.of());
    when(reflectionService.listReflections()).thenReturn(List.of(reflection));
    when(reflectionService.getReflection(reflection.uuid())).thenReturn(reflection);
    when(reflectionService.getGroup(groupUuid)).thenReturn(group);
    when(reflectionService.listGroups()).thenReturn(List.of(group));
    when(reflectionService.bindingsForGroup(groupUuid)).thenReturn(List.of(binding));
    when(reflectionService.getBindingByUuid(bindingName)).thenReturn(null);
    when(reflectionService.getBindingByUuid(bindingUuid)).thenReturn(binding);
    when(reflectionService.getReflectionById(reflectionToolName)).thenReturn(reflection);
    when(reflectionToolCallbackFactory.create(reflection, List.of(binding))).thenReturn(reflectionCallback);

    ToolCallback resolved = service.resolveDynamicToolCallbackForSession(sessionUuid, reflectionToolName);

    assertSame(reflectionCallback, resolved);
    }

    @Test
    void resolveDynamicToolCallbackForSession_resolvesReflectionToolViaGroupLineageFallback() {
    @SuppressWarnings("unchecked")
    DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
    @SuppressWarnings("unchecked")
    DatabaseRepository<AgentTemplate> agentTemplateRepo = mock(DatabaseRepository.class);
    @SuppressWarnings("unchecked")
    DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);

    SessionToolStore sessionToolStore = mock(SessionToolStore.class);
    sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory = mock(sh.vork.skill.SkillToolCallbackFactory.class);
    ReflectionService reflectionService = mock(ReflectionService.class);
    ReflectionToolCallbackFactory reflectionToolCallbackFactory = mock(ReflectionToolCallbackFactory.class);

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
        null,
        null,
        null);

    ReflectionTestUtils.setField(service, "reflectionService", reflectionService);
    ReflectionTestUtils.setField(service, "reflectionToolCallbackFactory", reflectionToolCallbackFactory);

    String sessionUuid = "session-reflection-lineage";
    String oldGroupUuid = "group-calendar-old";
    String newGroupUuid = "group-calendar-new";
    String bindingUuid = "binding-calendar";
    String reflectionToolName = "calendarLookup";

    AiSession session = new AiSession(
        sessionUuid,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "admin",
        "Untitled",
        System.currentTimeMillis(),
        0,
        List.of(),
        Map.of("SESSION_REFLECTION_BINDING_UUIDS", bindingUuid),
        null,
        "agent-tpl-concierge-001",
        null,
        null,
        null,
        null);

    AgentTemplate concierge = new AgentTemplate(
        "agent-tpl-concierge-001",
        "Concierge",
        "",
        List.of("listFiles"),
        true,
        List.of(),
        AgentType.INTERACTIVE,
        List.of());

    ReflectionBinding binding = new ReflectionBinding(
        bindingUuid,
        "binding-reflection-uuid",
        "Google Calendar",
        "https://example.invalid",
        Map.of(),
        1L,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ReflectionGroup oldGroup = new ReflectionGroup(
        oldGroupUuid,
        "calendar",
        "Calendar",
        "",
        ReflectionType.REST,
        "https://example.invalid",
        true,
        List.of(),
        List.of(),
        null,
        null,
        "google",
        "calendar",
        "1.0.0",
        null,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ReflectionGroup newGroup = new ReflectionGroup(
        newGroupUuid,
        "calendar",
        "Calendar",
        "",
        ReflectionType.REST,
        "https://example.invalid",
        true,
        List.of(),
        List.of(),
        null,
        null,
        "google",
        "calendar",
        "2.0.0",
        null,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    Reflection reflection = new Reflection(
        "reflection-uuid",
        reflectionToolName,
        "Calendar Lookup",
        "",
        newGroupUuid,
        List.of(),
        "GET",
        "https://example.invalid/calendar",
        Map.of(),
        Map.of(),
        "",
        "application/json",
        "application/json",
        "{}",
        1L,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ToolCallback reflectionCallback = new ToolCallback() {
        private final ToolDefinition definition = DefaultToolDefinition.builder()
            .name(reflectionToolName)
            .description("calendar")
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
    when(agentTemplateRepo.get("agent-tpl-concierge-001")).thenReturn(concierge);
    when(sessionToolStore.getTools(sessionUuid)).thenReturn(List.of());

    when(reflectionService.getBindingByUuid(bindingUuid)).thenReturn(binding);
    when(reflectionService.getReflection("binding-reflection-uuid")).thenReturn(
        new Reflection(
            "binding-reflection-uuid",
            "calendarBindingReference",
            "Calendar Binding Reference",
            "",
            oldGroupUuid,
            List.of(),
            "GET",
            "https://example.invalid/calendar/ref",
            Map.of(),
            Map.of(),
            "",
            "application/json",
            "application/json",
            "{}",
            1L,
            System.currentTimeMillis(),
            System.currentTimeMillis()));
    when(reflectionService.listReflections()).thenReturn(List.of(reflection));
    when(reflectionService.getReflectionById(reflectionToolName)).thenReturn(reflection);
    when(reflectionService.getGroup(oldGroupUuid)).thenReturn(oldGroup);
    when(reflectionService.getGroup(newGroupUuid)).thenReturn(newGroup);
    when(reflectionService.listGroups()).thenReturn(List.of(oldGroup, newGroup));
    when(reflectionService.bindingsForGroup(oldGroupUuid)).thenReturn(List.of(binding));
    when(reflectionService.bindingsForGroup(newGroupUuid)).thenReturn(List.of());

    when(reflectionToolCallbackFactory.create(reflection, List.of(binding))).thenReturn(reflectionCallback);

    ToolCallback resolved = service.resolveDynamicToolCallbackForSession(sessionUuid, reflectionToolName);

    assertSame(reflectionCallback, resolved);
    }

    @Test
    void resolveDynamicToolCallbackForSession_resolvesReflectionToolViaToolIdFallback() {
    @SuppressWarnings("unchecked")
    DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
    @SuppressWarnings("unchecked")
    DatabaseRepository<AgentTemplate> agentTemplateRepo = mock(DatabaseRepository.class);
    @SuppressWarnings("unchecked")
    DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);

    SessionToolStore sessionToolStore = mock(SessionToolStore.class);
    sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory = mock(sh.vork.skill.SkillToolCallbackFactory.class);
    ReflectionService reflectionService = mock(ReflectionService.class);
    ReflectionToolCallbackFactory reflectionToolCallbackFactory = mock(ReflectionToolCallbackFactory.class);

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
        null,
        null,
        null);

    ReflectionTestUtils.setField(service, "reflectionService", reflectionService);
    ReflectionTestUtils.setField(service, "reflectionToolCallbackFactory", reflectionToolCallbackFactory);

    String sessionUuid = "session-reflection-toolid";
    String staleGroupUuid = "group-stale";
    String activeGroupUuid = "group-active";
    String bindingUuid = "binding-calendar";
    String reflectionToolName = "calendarLookup";

    AiSession session = new AiSession(
        sessionUuid,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "admin",
        "Untitled",
        System.currentTimeMillis(),
        0,
        List.of(),
        Map.of("SESSION_REFLECTION_BINDING_UUIDS", bindingUuid),
        null,
        "agent-tpl-concierge-001",
        null,
        null,
        null,
        null);

    AgentTemplate concierge = new AgentTemplate(
        "agent-tpl-concierge-001",
        "Concierge",
        "",
        List.of("listFiles"),
        true,
        List.of(),
        AgentType.INTERACTIVE,
        List.of());

    ReflectionBinding binding = new ReflectionBinding(
        bindingUuid,
        "binding-reflection-uuid-stale",
        "Google Calendar",
        "https://example.invalid",
        Map.of(),
        1L,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ReflectionGroup staleGroup = new ReflectionGroup(
        staleGroupUuid,
        "calendar",
        "Calendar Old",
        "",
        ReflectionType.REST,
        "https://example.invalid",
        true,
        List.of(),
        List.of(),
        null,
        null,
        "otherA",
        "otherB",
        "1.0.0",
        null,
        System.currentTimeMillis() - 1000,
        System.currentTimeMillis() - 1000);

    ReflectionGroup activeGroup = new ReflectionGroup(
        activeGroupUuid,
        "calendar",
        "Calendar Active",
        "",
        ReflectionType.REST,
        "https://example.invalid",
        true,
        List.of(),
        List.of(),
        null,
        null,
        "otherA",
        "otherB",
        "2.0.0",
        null,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    Reflection reflection = new Reflection(
        "reflection-uuid",
        reflectionToolName,
        "Calendar Lookup",
        "",
        activeGroupUuid,
        List.of(),
        "GET",
        "https://example.invalid/calendar",
        Map.of(),
        Map.of(),
        "",
        "application/json",
        "application/json",
        "{}",
        1L,
        System.currentTimeMillis(),
        System.currentTimeMillis());

    ToolCallback reflectionCallback = new ToolCallback() {
        private final ToolDefinition definition = DefaultToolDefinition.builder()
            .name(reflectionToolName)
            .description("calendar")
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
    when(agentTemplateRepo.get("agent-tpl-concierge-001")).thenReturn(concierge);
    when(sessionToolStore.getTools(sessionUuid)).thenReturn(List.of());

    when(reflectionService.getBindingByUuid(bindingUuid)).thenReturn(binding);
    when(reflectionService.getReflection("binding-reflection-uuid-stale")).thenReturn(
        new Reflection(
            "binding-reflection-uuid-stale",
            "calendarBindingReference",
            "Calendar Binding Reference",
            "",
            staleGroupUuid,
            List.of(),
            "GET",
            "https://example.invalid/calendar/ref",
            Map.of(),
            Map.of(),
            "",
            "application/json",
            "application/json",
            "{}",
            1L,
            System.currentTimeMillis(),
            System.currentTimeMillis()));
    when(reflectionService.listReflections()).thenReturn(List.of(reflection));
    when(reflectionService.getReflectionById(reflectionToolName)).thenReturn(reflection);
    when(reflectionService.getGroup(staleGroupUuid)).thenReturn(staleGroup);
    when(reflectionService.getGroup(activeGroupUuid)).thenReturn(activeGroup);
    when(reflectionService.getGroup("binding-reflection-uuid-stale")).thenReturn(staleGroup);
    when(reflectionService.listGroups()).thenReturn(List.of(staleGroup, activeGroup));
    when(reflectionService.bindingsForGroup(staleGroupUuid)).thenReturn(List.of(binding));
    when(reflectionService.bindingsForGroup(activeGroupUuid)).thenReturn(List.of());

    when(reflectionToolCallbackFactory.create(reflection, List.of(binding))).thenReturn(reflectionCallback);

    ToolCallback resolved = service.resolveDynamicToolCallbackForSession(sessionUuid, reflectionToolName);

    assertSame(reflectionCallback, resolved);
    }

        @Test
        void resolveDynamicToolCallbackForSession_doesNotUseLineageFallbackForRecordReflections() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentTemplateRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);

        SessionToolStore sessionToolStore = mock(SessionToolStore.class);
        sh.vork.skill.SkillToolCallbackFactory skillToolCallbackFactory = mock(sh.vork.skill.SkillToolCallbackFactory.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ReflectionToolCallbackFactory reflectionToolCallbackFactory = mock(ReflectionToolCallbackFactory.class);

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
            null,
            null,
            null);

        ReflectionTestUtils.setField(service, "reflectionService", reflectionService);
        ReflectionTestUtils.setField(service, "reflectionToolCallbackFactory", reflectionToolCallbackFactory);

        String sessionUuid = "session-record-lineage";
        String oldGroupUuid = "group-record-old";
        String newGroupUuid = "group-record-new";
        String bindingUuid = "binding-record";
        String reflectionToolName = "recordSearchCustomer";

        AiSession session = new AiSession(
            sessionUuid,
            AiProvider.GEMINI.name(),
            SessionOriginMode.WEB,
            "admin",
            "Untitled",
            System.currentTimeMillis(),
            0,
            List.of(),
            Map.of("SESSION_REFLECTION_BINDING_UUIDS", bindingUuid),
            null,
            "agent-tpl-concierge-001",
            null,
            null,
            null,
            null);

        AgentTemplate concierge = new AgentTemplate(
            "agent-tpl-concierge-001",
            "Concierge",
            "",
            List.of("listFiles"),
            true,
            List.of(),
            AgentType.INTERACTIVE,
            List.of());

        ReflectionBinding binding = new ReflectionBinding(
            bindingUuid,
            "binding-reflection-uuid",
            "Record Binding",
            "",
            Map.of(),
            1L,
            System.currentTimeMillis(),
            System.currentTimeMillis());

        ReflectionGroup oldGroup = new ReflectionGroup(
            oldGroupUuid,
            "recordcustomer",
            "Customer Record",
            "",
            ReflectionType.RECORD,
            "",
            true,
            List.of(),
            List.of(),
            null,
            null,
            "customer",
            "record",
            "1.0.0",
            null,
            System.currentTimeMillis(),
            System.currentTimeMillis());

        ReflectionGroup newGroup = new ReflectionGroup(
            newGroupUuid,
            "recordcustomer",
            "Customer Record",
            "",
            ReflectionType.RECORD,
            "",
            true,
            List.of(),
            List.of(),
            null,
            null,
            "customer",
            "record",
            "2.0.0",
            null,
            System.currentTimeMillis(),
            System.currentTimeMillis());

        Reflection reflection = new Reflection(
            "reflection-uuid",
            reflectionToolName,
            "Record Search",
            "",
            newGroupUuid,
            List.of(),
            "GET",
            "/api/types/sh.vork.generated.Customer/search",
            Map.of(),
            Map.of(),
            "",
            "application/json",
            "application/json",
            "{}",
            1L,
            System.currentTimeMillis(),
            System.currentTimeMillis());

        when(sessionRepo.get(sessionUuid)).thenReturn(session);
        when(agentTemplateRepo.get("agent-tpl-concierge-001")).thenReturn(concierge);
        when(sessionToolStore.getTools(sessionUuid)).thenReturn(List.of());

        when(reflectionService.getBindingByUuid(bindingUuid)).thenReturn(binding);
        when(reflectionService.getReflection("binding-reflection-uuid")).thenReturn(
            new Reflection(
                "binding-reflection-uuid",
                "recordBindingReference",
                "Record Binding Reference",
                "",
                oldGroupUuid,
                List.of(),
                "GET",
                "/api/types/sh.vork.generated.Customer/search",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                "{}",
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        when(reflectionService.listReflections()).thenReturn(List.of(reflection));
        when(reflectionService.getReflectionById(reflectionToolName)).thenReturn(reflection);
        when(reflectionService.getGroup(oldGroupUuid)).thenReturn(oldGroup);
        when(reflectionService.getGroup(newGroupUuid)).thenReturn(newGroup);
        when(reflectionService.listGroups()).thenReturn(List.of(oldGroup, newGroup));
        when(reflectionService.bindingsForGroup(oldGroupUuid)).thenReturn(List.of(binding));
        when(reflectionService.bindingsForGroup(newGroupUuid)).thenReturn(List.of());

        ToolCallback resolved = service.resolveDynamicToolCallbackForSession(sessionUuid, reflectionToolName);

        assertNull(resolved);
        }

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
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentTemplateRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<sh.vork.skill.Skill> skillRepo = mock(DatabaseRepository.class);

        AiOrchestrationService service = new AiOrchestrationService(
                Map.<AiProvider, ChatClient>of(),
                null,
                null,
                sessionRepo,
            agentTemplateRepo,
            skillRepo,
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
