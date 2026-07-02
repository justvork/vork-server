package sh.vork.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseRepository;

import java.util.Comparator;
import java.util.List;

@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final DatabaseRepository<VorkUser> userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(DatabaseRepository<VorkUser> userRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserSummary> listUsers() {
        log.debug("ENTER listUsers");
        try (var users = userRepository.list(0, Integer.MAX_VALUE)) {
            List<UserSummary> result = users
                    .map(UserManagementService::toSummary)
                    .sorted(Comparator.comparing(UserSummary::username, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            log.debug("EXIT listUsers: count={}", result.size());
            return result;
        }
    }

    public VorkUser createUser(String username, String password, String roleValue) {
        log.debug("ENTER createUser: username={}, role={}", username, roleValue);
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        if (userRepository.get(trimmed) != null) {
            throw new IllegalArgumentException("Username already exists: " + trimmed);
        }

        UserRole role = UserRole.fromStoredValue(roleValue);
        long now = System.currentTimeMillis();
        VorkUser created = new VorkUser(
                trimmed,
                passwordEncoder.encode(password),
                role.name(),
            true,
                now,
            now
        );
        userRepository.save(created);
        log.info("User created: username={}, role={}", trimmed, role.name());
        return created;
    }

    public VorkUser updateRole(String username, String roleValue) {
        log.debug("ENTER updateRole: username={}, role={}", username, roleValue);
        VorkUser existing = requireUser(username);
        UserRole currentRole = UserRole.fromStoredValue(existing.role());
        UserRole targetRole = UserRole.fromStoredValue(roleValue);

        if (currentRole == UserRole.ADMIN && targetRole != UserRole.ADMIN && isLastActiveAdmin(existing.uuid())) {
            throw new IllegalArgumentException("Cannot demote the last active admin user.");
        }

        VorkUser updated = new VorkUser(
                existing.uuid(),
                existing.passwordHash(),
                targetRole.name(),
            existing.isEnabled(),
                existing.createdAt(),
            System.currentTimeMillis()
        );
        userRepository.save(updated);
        log.info("User role updated: username={}, role={}", username, targetRole.name());
        return updated;
    }

    public VorkUser setEnabled(String username, boolean enabled) {
        log.debug("ENTER setEnabled: username={}, enabled={}", username, enabled);
        VorkUser existing = requireUser(username);
        UserRole currentRole = UserRole.fromStoredValue(existing.role());

        if (!enabled && currentRole == UserRole.ADMIN && isLastActiveAdmin(existing.uuid())) {
            throw new IllegalArgumentException("Cannot disable the last active admin user.");
        }

        VorkUser updated = new VorkUser(
                existing.uuid(),
                existing.passwordHash(),
                existing.role(),
            enabled,
                existing.createdAt(),
            System.currentTimeMillis()
        );
        userRepository.save(updated);
        log.info("User enabled state updated: username={}, enabled={}", username, enabled);
        return updated;
    }

    public VorkUser resetPassword(String username, String newPassword) {
        log.debug("ENTER resetPassword: username={}", username);
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }

        VorkUser existing = requireUser(username);
        VorkUser updated = new VorkUser(
                existing.uuid(),
                passwordEncoder.encode(newPassword),
                existing.role(),
            existing.isEnabled(),
                existing.createdAt(),
            System.currentTimeMillis()
        );
        userRepository.save(updated);
        log.info("User password reset by admin: username={}", username);
        return updated;
    }

    public void deleteUser(String username) {
        log.debug("ENTER deleteUser: username={}", username);
        VorkUser existing = requireUser(username);
        UserRole currentRole = UserRole.fromStoredValue(existing.role());

        if (currentRole == UserRole.ADMIN && isLastActiveAdmin(existing.uuid())) {
            throw new IllegalArgumentException("Cannot delete the last active admin user.");
        }

        userRepository.delete(existing.uuid());
        log.info("User deleted: username={}", username);
    }

    private VorkUser requireUser(String username) {
        VorkUser user = userRepository.get(username);
        if (user == null) {
            throw new IllegalArgumentException("User not found: " + username);
        }
        return user;
    }

    private boolean isLastActiveAdmin(String candidateUsername) {
        long activeAdminCount;
        try (var users = userRepository.list(0, Integer.MAX_VALUE)) {
            activeAdminCount = users
                    .filter(VorkUser::isEnabled)
                    .filter(user -> UserRole.fromStoredValue(user.role()) == UserRole.ADMIN)
                    .count();
        }
        VorkUser candidate = userRepository.get(candidateUsername);
        if (candidate == null) {
            return false;
        }
        return candidate.isEnabled()
                && UserRole.fromStoredValue(candidate.role()) == UserRole.ADMIN
                && activeAdminCount <= 1;
    }

    private static UserSummary toSummary(VorkUser user) {
        return new UserSummary(
                user.uuid(),
                UserRole.fromStoredValue(user.role()).name(),
                user.isEnabled(),
                user.createdAt(),
                user.updatedAt()
        );
    }

    public record UserSummary(String username, String role, boolean enabled, long createdAt, long updatedAt) {}
}
