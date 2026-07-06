package sh.vork.skill;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.mock.MapDatabaseRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SkillServiceReachabilityTest {

    @Test
    void privateSkill_reachableFromAttachedPublicRoot_isExecutableAtRuntime() {
        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        MapDatabaseRepository<AgentTemplate> agentRepo = new MapDatabaseRepository<>(AgentTemplate.class);

        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);
        ReflectionTestUtils.setField(service, "agentTemplateRepo", agentRepo);
        ReflectionTestUtils.setField(service, "typeGeneratorService", mock(sh.vork.typegen.TypeGeneratorService.class));

        Skill privateConnect = new Skill(
                "skill-connect",
                "Connect",
                "Private connect skill",
                "grp-calendar",
                SkillVisibility.PRIVATE,
                List.of(),
                "connect",
                List.of(),
                List.of(),
                List.of(),
                1L,
                1L,
                1L,
                List.of());

        Skill publicSearch = new Skill(
                "skill-search",
                "Search",
                "Public search skill",
                "grp-calendar",
                SkillVisibility.PUBLIC,
                List.of(),
                "search",
                List.of(),
                List.of(),
                List.of("skill-connect"),
                1L,
                1L,
                1L,
                List.of());

        skillRepo.save(privateConnect);
        skillRepo.save(publicSearch);

        agentRepo.save(new AgentTemplate(
                "agent-1",
                "Calendar Agent",
                "",
                List.of(),
                false,
                List.of("skill-search"),
                AgentType.INTERACTIVE));

        String sessionUuid = "session-1";
        sessionRepo.save(new AiSession(
                sessionUuid,
                "GEMINI",
                SessionOriginMode.WEB,
                "lee",
                "Test Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(),
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of()));

        ToolExecutionContext.bindSessionUuid(sessionUuid);
        try {
            SkillActivatedException ex = assertThrows(
                    SkillActivatedException.class,
                    () -> service.executeSkill("skill-connect", Map.of()));
            assertEquals("skill-connect", ex.getSkillUuid());
        } finally {
            ToolExecutionContext.clear();
        }
    }
}
