package sh.vork.ai.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.security.VorkUser;

class AuthorizationRuleEngineRolePolicyTest {

    private MapDatabaseRepository<VorkUser> userRepository;

    @BeforeEach
    void setUp() {
        userRepository = new MapDatabaseRepository<>(VorkUser.class);
        long now = System.currentTimeMillis();
        userRepository.save(new VorkUser("admin", "hash", "ADMIN", true, now, now));
        userRepository.save(new VorkUser("alice", "hash", "USER", true, now, now));
    }

    @Test
    void rolePolicy_deniesUserForSkillWriteTool() {
        AuthorizationRuleEngine engine = new AuthorizationRuleEngine(Set.of("createSkill"), userRepository);

        assertFalse(engine.isRolePermitted("createSkill", "alice"));
    }

    @Test
    void rolePolicy_allowsAdminForSkillWriteTool() {
        AuthorizationRuleEngine engine = new AuthorizationRuleEngine(Set.of("createSkill"), userRepository);

        assertTrue(engine.isRolePermitted("createSkill", "admin"));
    }

    @Test
    void rolePolicy_allowsUnmappedToolsForUser() {
        AuthorizationRuleEngine engine = new AuthorizationRuleEngine(Set.of("createSshConnection"), userRepository);

        assertTrue(engine.isRolePermitted("createSshConnection", "alice"));
    }
}
