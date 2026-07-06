package sh.vork.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import sh.vork.ai.entity.AiSession;
import sh.vork.orm.mock.MapDatabaseRepository;

class SkillServiceListGroupsSortTest {

    @Test
    void listGroups_returnsGroupsSortedByName_caseInsensitive() {
        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);

        groupRepo.save(new SkillGroup("grp-z", "zeta", "ops", "Automation", List.of(), 1L, 1L, 1L));
        groupRepo.save(new SkillGroup("grp-a", "Alpha", "ops", "Automation", List.of(), 1L, 1L, 1L));
        groupRepo.save(new SkillGroup("grp-b", "beta", "ops", "Automation", List.of(), 1L, 1L, 1L));

        List<SkillGroup> groups = service.listGroups();

        assertEquals(3, groups.size());
        assertEquals("Alpha", groups.get(0).name());
        assertEquals("beta", groups.get(1).name());
        assertEquals("zeta", groups.get(2).name());
    }
}
