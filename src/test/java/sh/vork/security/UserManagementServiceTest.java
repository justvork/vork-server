package sh.vork.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;

class UserManagementServiceTest {

    private DatabaseRepository<VorkUser> userRepository;
    private PasswordEncoder passwordEncoder;
    private UserManagementService userManagementService;

    @BeforeEach
    void setUp() {
        userRepository = new MapDatabaseRepository<>(VorkUser.class);
        passwordEncoder = new BCryptPasswordEncoder();
        userManagementService = new UserManagementService(userRepository, passwordEncoder);

        long now = System.currentTimeMillis();
        userRepository.save(new VorkUser("admin", passwordEncoder.encode("supersecret"), "ADMIN", true, now, now));
    }

    @Test
    void createUserPersistsEncodedPasswordAndRole() {
        VorkUser created = userManagementService.createUser("alice", "password123", "USER");

        assertEquals("alice", created.uuid());
        assertEquals("USER", created.role());
        assertTrue(created.isEnabled());
        assertTrue(passwordEncoder.matches("password123", created.passwordHash()));
    }

    @Test
    void cannotDemoteLastEnabledAdmin() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userManagementService.updateRole("admin", "USER"));
        assertEquals("Cannot demote the last active admin user.", ex.getMessage());
    }

    @Test
    void cannotDeleteLastEnabledAdmin() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userManagementService.deleteUser("admin"));
        assertEquals("Cannot delete the last active admin user.", ex.getMessage());
    }

    @Test
    void canDemoteAdminWhenAnotherAdminExists() {
        userManagementService.createUser("backup", "password123", "ADMIN");

        VorkUser updated = userManagementService.updateRole("admin", "USER");

        assertEquals("USER", updated.role());
    }
}
