package sh.vork.skill;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.orm.mock.MapDatabaseRepository;

class SkillServiceParameterValidationTest {

    @Test
    void executeSkillRejectsInvalidIsoDate() {
        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);

        skillRepo.save(new Skill(
                "skill-date",
                "Date Skill",
                "desc",
                "group-1",
                SkillVisibility.PUBLIC,
                List.of(new SkillParameter("startDate", "date", "Start date", SkillParameterInputMode.AI_REQUIRED)),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                List.of()));

        String result = service.executeSkill("skill-date", Map.of("startDate", "2025/01/31"));

        assertTrue(result.contains("\"status\":\"invalid_parameters\""));
        assertTrue(result.contains("startDate expects YYYY-MM-DD"));
    }

    @Test
    void executeSkillRejectsInvalidIsoTimestamp() {
        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);

        skillRepo.save(new Skill(
                "skill-ts",
                "Timestamp Skill",
                "desc",
                "group-1",
                SkillVisibility.PUBLIC,
                List.of(new SkillParameter("runAt", "timestamp", "Run at", SkillParameterInputMode.AI_REQUIRED)),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                List.of()));

        String result = service.executeSkill("skill-ts", Map.of("runAt", "2025-01-31T10:15:30"));

        assertTrue(result.contains("\"status\":\"invalid_parameters\""));
        assertTrue(result.contains("runAt expects ISO 8601 date-time with timezone/offset"));
    }

    @Test
    void executeSkillAcceptsValidIsoDateAndTimestampFormats() {
        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);

        skillRepo.save(new Skill(
                "skill-valid",
                "Valid Typed Inputs",
                "desc",
                "group-1",
                SkillVisibility.PUBLIC,
                List.of(
                        new SkillParameter("day", "date", "Day", SkillParameterInputMode.AI_REQUIRED),
                        new SkillParameter("scheduledAt", "timestamp", "Timestamp", SkillParameterInputMode.AI_REQUIRED)),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                List.of()));

        String result = service.executeSkill("skill-valid", Map.of(
                "day", "2025-01-31",
                "scheduledAt", "2025-01-31T10:15:30Z"));

        assertTrue(result.contains("active session"));
        assertTrue(!result.contains("invalid_parameters"));

        ToolExecutionContext.clear();
    }
}