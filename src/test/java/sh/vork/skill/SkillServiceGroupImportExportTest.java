package sh.vork.skill;

import org.junit.jupiter.api.Test;
import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class SkillServiceGroupImportExportTest {

    @Test
    void importGroup_rejectsAtomically_whenDependenciesMissing() {
        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);

        Skill readSkill = new Skill(
                "skill-read",
                "Read Gmail",
                "Read inbox messages",
                "grp-gmail",
                false,
                List.of(),
                "Read messages",
                List.of(),
                List.of(),
                List.of("skill-connect"),
                1,
                1000,
                1000,
                List.of());

        SkillGroup group = new SkillGroup(
                "grp-gmail",
                "Gmail Skills",
                "ops",
                "Productivity",
                List.of(readSkill),
                1,
                1000,
                1000);

        SkillService.SkillGroupExportPackage pkg = new SkillService.SkillGroupExportPackage(
                "1.0",
                group,
                List.of());

        SkillService.SkillGroupImportResult result = service.importGroup(pkg);

        assertEquals("missing_dependencies", result.status());
        assertEquals("grp-gmail", result.groupUuid());
        assertEquals(1, result.missingDependencies().size());
        assertNull(groupRepo.get("grp-gmail"));
        assertNull(skillRepo.get("skill-read"));
    }

    @Test
    void exportGroup_includesGroupAndMemberSkills() {
        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);

        SkillGroup group = new SkillGroup(
                "grp-mail",
                "Mail Skills",
                "team-a",
                "Productivity",
                List.of(),
                1,
                1000,
                1000);
        groupRepo.save(group);

        skillRepo.save(new Skill(
                "skill-connect",
                "Connect Mail",
                "Connect account",
                "grp-mail",
                true,
                List.of(),
                "Connect",
                List.of("createOAuthConnection"),
                List.of(),
                List.of(),
                1,
                1000,
                1000,
                List.of()));

        skillRepo.save(new Skill(
                "skill-send",
                "Send Mail",
                "Send message",
                "grp-mail",
                false,
                List.of(),
                "Send",
                List.of("sendNotification"),
                List.of(),
                List.of("skill-connect"),
                1,
                1000,
                1000,
                List.of()));

        SkillService.SkillGroupExportPackage exported = service.exportGroup("grp-mail");

        assertNotNull(exported);
        assertEquals("grp-mail", exported.group().uuid());
        assertEquals(2, exported.group().skills().size());
        assertEquals(0, exported.types().size());
    }
}
