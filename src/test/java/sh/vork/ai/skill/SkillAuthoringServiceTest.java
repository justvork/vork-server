package sh.vork.ai.skill;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import sh.vork.ai.AiProvider;
import sh.vork.ai.function.DesignSkillRequest;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.ai.registry.ToolDescriptor;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillGroup;
import sh.vork.skill.SkillService;
import sh.vork.skill.SkillVisibility;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillAuthoringServiceTest {

    @Test
    void designSkillFromRequest_dryRunOnly() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillService skillService = mock(SkillService.class);

        when(toolRegistry.getAvailableTools()).thenReturn(List.of(
                descriptor("listAvailableTools", List.of()),
                descriptor("executeTerminalCommand", List.of())));
        when(skillService.list()).thenReturn(List.of());
        when(skillService.listGroups()).thenReturn(List.of(
                new SkillGroup("group-1", "Operations Skills", "lee", "Operations", List.of(), 1, 1, 1)));

        SkillAuthoringService service = new SkillAuthoringService(toolRegistry, skillService);

        SkillAuthoringService.SkillAuthoringResult result = service.designSkillFromRequest(
                "lee",
                new DesignSkillRequest("run terminal diagnostics on host", null, null, null, null, null, true));

        assertEquals("dry_run", result.status());
        assertTrue(result.feasible());
        assertTrue(result.dryRun());
        assertTrue(result.skillUuid() == null || result.skillUuid().isBlank());
        assertEquals("group-1", result.resolvedGroupUuid());
        assertEquals("Operations Skills", result.resolvedGroupName());
        assertFalse(result.groupCreated());
        assertNotNull(result.generatedSkillRequest());
        assertFalse(result.generatedSkillRequest().allowedTools().isEmpty());
    }

    @Test
        void designSkillFromRequest_usesVisibilityOverrideFromInput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillService skillService = mock(SkillService.class);

        when(toolRegistry.getAvailableTools()).thenReturn(List.of(
                descriptor("createMongoDBConnection", List.of())
        ));
        when(skillService.list()).thenReturn(List.of());
        when(skillService.listGroups()).thenReturn(List.of(
                new SkillGroup("group-data-1", "Data Skills", "lee", "Data", List.of(), 1, 1, 1)
        ));

        SkillAuthoringService service = new SkillAuthoringService(toolRegistry, skillService);

        SkillAuthoringService.SkillAuthoringResult result = service.designSkillFromRequest(
                "lee",
                new DesignSkillRequest("create mongo connection helper", null, "Data", null, null, SkillVisibility.PRIVATE, true));

        assertEquals("dry_run", result.status());
        assertNotNull(result.generatedSkillRequest());
        assertEquals(SkillVisibility.PRIVATE, result.generatedSkillRequest().visibility());
    }

    @Test
    void designSkillFromRequest_aiSelectsToolsFromRequirements() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        SkillService skillService = mock(SkillService.class);
        AiChatClientFactory aiChatClientFactory = mock(AiChatClientFactory.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

        when(toolRegistry.getAvailableTools()).thenReturn(List.of(
                descriptor("oauthConnect", List.of("httpRequest"), oauthSkillPolicyDescription()),
                descriptor("httpRequest", List.of()),
                descriptor("createSshConnection", List.of()),
                descriptor("saveTypeInstance", List.of())
        ));
        when(skillService.list()).thenReturn(List.of(
                new Skill("skill-calendar-1", "Google Calendar Read", "Reads Google Calendar events", "grp-google", SkillVisibility.PUBLIC,
                        List.of(new sh.vork.skill.SkillParameter("date", "string", "Date input")),
                        "Use oauthConnect then httpRequest for Google APIs",
                        List.of("oauthConnect", "httpRequest"),
                        List.of(),
                        List.of(),
                        1,
                        1,
                        1,
                        List.of())
        ));
        when(skillService.listGroups()).thenReturn(List.of(
                new SkillGroup("grp-google", "Google Integrations", "lee", "Automation", List.of(), 1, 1, 1)
        ));

        when(aiChatClientFactory.getBaseClient(AiProvider.GEMINI)).thenReturn(chatClient);
        when(chatClient.mutate().defaultSystem(org.mockito.ArgumentMatchers.anyString()).build().prompt()
                .user(org.mockito.ArgumentMatchers.anyString()).call().content())
                .thenReturn("""
                        {
                          "selectedTools": ["oauthConnect"],
                          "skillName": "Gmail Skill",
                          "description": "Uses OAuth and Gmail API",
                          "parameters": [
                            {"name":"operation","type":"string","description":"action"},
                            {"name":"providerName","type":"string","description":"provider"},
                            {"name":"scopes","type":"string","description":"scopes"}
                          ],
                          "instructions": "Use oauthConnect then httpRequest.",
                          "outputContract": "status, records",
                          "autoShareWithinGroup": true
                        }
                        """);

        SkillAuthoringService service = new SkillAuthoringService(
                toolRegistry,
                skillService,
                aiChatClientFactory,
                new com.fasterxml.jackson.databind.ObjectMapper());

        SkillAuthoringService.SkillAuthoringResult result = service.designSkillFromRequest(
                "lee",
                new DesignSkillRequest(
                        "create a skill to connect to gmail and fetch messages",
                        null,
                        "Automation",
                        "Google Integrations",
                        null,
                        null,
                        true));

        assertEquals("dry_run", result.status());
        List<String> selectedTools = result.generatedSkillRequest().allowedTools();
        assertTrue(selectedTools.contains("oauthConnect"));
        assertTrue(selectedTools.contains("httpRequest"));
        assertFalse(selectedTools.contains("createSshConnection"));
        assertFalse(selectedTools.contains("saveTypeInstance"));

        List<String> parameterNames = result.generatedSkillRequest().parameters().stream()
                .map(sh.vork.skill.SkillParameter::name)
                .toList();
        assertTrue(parameterNames.contains("operation"));
        assertFalse(parameterNames.contains("providerName"));
        assertFalse(parameterNames.contains("scopes"));

        String instructions = result.generatedSkillRequest().instructions();
        assertTrue(instructions.contains("Tool usage guidance:"));
        assertTrue(instructions.contains("authorizeEndpoint"));
        assertTrue(instructions.contains("tokenEndpoint"));
        assertTrue(instructions.contains("scopes"));
    }

    private static ToolDescriptor descriptor(String id, List<String> dependsOn) {
        return descriptor(id, dependsOn, "desc");
    }

    private static ToolDescriptor descriptor(String id, List<String> dependsOn, String description) {
        return new ToolDescriptor(id, id, id, "Test", description, "{}", false, false, dependsOn);
    }

    private static String oauthSkillPolicyDescription() {
        return """
                desc
                SKILL_USAGE_NON_RUNTIME_INPUTS: providerName, provider, clientName, scopes, authorizeEndpoint, tokenEndpoint, redirectUri, authorizationParams, clientId, clientSecret
                SKILL_USAGE_GUIDANCE: Infer provider defaults and pass them directly in the oauthConnect tool call (clientName, authorizeEndpoint, tokenEndpoint, scopes, authorizationParams) rather than exposing them as runtime skill inputs.
                """;
    }
}
