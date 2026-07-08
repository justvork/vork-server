package sh.vork.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.orm.DatabaseRepository;

class SkillToolCallbackFactoryTest {

    private SkillService skillService;
    private DatabaseRepository<SkillGroup> skillGroupRepository;
    private SkillToolCallbackFactory factory;

    @BeforeEach
    void setUp() {
        skillService = Mockito.mock(SkillService.class);
        skillGroupRepository = Mockito.mock(DatabaseRepository.class);
        factory = new SkillToolCallbackFactory(new ObjectMapper());
        ReflectionTestUtils.setField(factory, "skillService", skillService);
        ReflectionTestUtils.setField(factory, "skillGroupRepository", skillGroupRepository);
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void executesWithoutSuspensionWhenPromptIfEmptyAlreadyProvided() {
        Skill skill = skillWithInputMode(SkillParameterInputMode.USER_PROMPT_IF_EMPTY);
        ToolCallback callback = factory.create(skill);

        when(skillService.executeSkill(eq(skill.uuid()), anyMap())).thenReturn("{\"status\":\"ok\"}");

        String result = callback.call("{\"query\":\"calendar\"}");

        assertEquals("{\"status\":\"ok\"}", result);
        verify(skillService).executeSkill(eq(skill.uuid()), anyMap());
    }

    @Test
    void suspendsAndBuildsFormWhenPromptIfEmptyValueIsMissing() {
        Skill skill = skillWithInputMode(SkillParameterInputMode.USER_PROMPT_IF_EMPTY);
        ToolCallback callback = factory.create(skill);

        ToolSuspensionException ex = assertThrows(
                ToolSuspensionException.class,
                () -> callback.call("{}"));

        assertEquals("COLLECT_SKILL_INPUT", ex.getFormSchema().intent());
        assertNotNull(ex.getFormSchema().fields());

        var queryField = ex.getFormSchema().fields().stream()
                .filter(field -> "query".equals(field.name()))
                .findFirst()
                .orElseThrow();
            assertEquals("", queryField.value());

        var tokenField = ex.getFormSchema().fields().stream()
                .filter(field -> "__skill_input_token".equals(field.name()))
                .findFirst()
                .orElseThrow();
        assertNotNull(tokenField.value());
        assertFalse(tokenField.value().isBlank());

        verify(skillService, never()).executeSkill(eq(skill.uuid()), anyMap());
    }

    @Test
    void executesSkillAfterResumeTokenIsSubmitted() {
        Skill skill = skillWithInputMode(SkillParameterInputMode.USER_PROMPT_IF_EMPTY);
        ToolCallback callback = factory.create(skill);

        ToolSuspensionException ex = assertThrows(
                ToolSuspensionException.class,
                () -> callback.call("{}"));

        String token = ex.getFormSchema().fields().stream()
                .filter(field -> "__skill_input_token".equals(field.name()))
                .map(field -> field.value() == null ? "" : field.value())
                .findFirst()
                .orElseThrow();

        when(skillService.executeSkill(eq(skill.uuid()), anyMap())).thenReturn("{\"status\":\"ok\"}");

        String result = callback.call("{\"query\":\"updated\",\"__skill_input_token\":\"" + token + "\"}");
        assertEquals("{\"status\":\"ok\"}", result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(skillService).executeSkill(eq(skill.uuid()), paramsCaptor.capture());

        Map<String, String> params = paramsCaptor.getValue();
        assertEquals("updated", params.get("query"));
        assertFalse(params.containsKey("__skill_input_token"));
    }

        @Test
        void usesTextareaFieldForTextParameterType() {
        Skill skill = new Skill(
            "skill-1",
            "Calendar Skill",
            "desc",
            "group-1",
            SkillVisibility.PUBLIC,
            List.of(new SkillParameter("query", "text", "Search query", SkillParameterInputMode.USER_ALWAYS_PROMPT)),
            "instructions",
            List.of(),
            List.of(),
            List.of(),
            1L,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            List.of());
        ToolCallback callback = factory.create(skill);

        ToolSuspensionException ex = assertThrows(
            ToolSuspensionException.class,
            () -> callback.call("{}"));

        var queryField = ex.getFormSchema().fields().stream()
            .filter(field -> "query".equals(field.name()))
            .findFirst()
            .orElseThrow();

        assertEquals("textarea", queryField.type());
        }

    private static Skill skillWithInputMode(SkillParameterInputMode inputMode) {
        return new Skill(
                "skill-1",
                "Calendar Skill",
                "desc",
                "group-1",
                SkillVisibility.PUBLIC,
                List.of(new SkillParameter("query", "string", "Search query", inputMode)),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                List.of());
    }
}
