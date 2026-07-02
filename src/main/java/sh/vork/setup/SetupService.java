package sh.vork.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseRepository;

import sh.vork.security.VorkUser;
import sh.vork.security.UserRole;

/**
 * Detects whether first-time setup is required and handles admin account creation.
 *
 * <p>Setup is required when no {@link VorkUser} documents exist in the database.
 * After the first user is created the result is cached in a volatile flag so
 * subsequent requests never hit the database for this check.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final DatabaseRepository<VorkUser> userRepo;
    private final PasswordEncoder passwordEncoder;
    private final DatabaseSetupService databaseSetupService;

    /** Cached flag — flipped to {@code true} once setup is confirmed complete. */
    private volatile boolean setupComplete = false;

    public SetupService(DatabaseRepository<VorkUser> userRepo,
                        PasswordEncoder passwordEncoder,
                        DatabaseSetupService databaseSetupService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.databaseSetupService = databaseSetupService;
    }

    /**
     * Returns {@code true} when setup must be run.
     *
     * <p>Setup is required when:
     * <ol>
     *   <li>The database backend has not been configured yet
     *       ({@code conf.d/database.properties} is absent), <em>and</em></li>
     *   <li>No admin users exist (a count of zero rules out legacy installs that
     *       pre-date the database-setup step).</li>
     * </ol>
     *
     * <p>If the properties file exists, the ordinary user-count check is used.
     * The result is cached once setup is confirmed complete.
     */
    public boolean isSetupRequired() {
        if (setupComplete) return false;
        if (!databaseSetupService.isDatabaseConfigured()) {
            // No conf.d/database.properties — could be a fresh install or a legacy
            // install that predates the setup wizard's database step.
            // If users already exist it's a legacy install — skip setup.
            try {
                if (userRepo.count() > 0) {
                    setupComplete = true;
                    return false;
                }
            } catch (Exception ignored) {
                // DB unreachable on a fresh install — setup is definitely required.
            }
            return true;
        }
        try {
            boolean required = userRepo.count() == 0;
            if (!required) setupComplete = true;
            return required;
        } catch (Exception e) {
            log.warn("Setup check failed, assuming complete: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Creates the initial admin user with the given credentials.
     *
     * @param username chosen admin username
     * @param password plain-text password (will be BCrypt-encoded)
     * @throws IllegalArgumentException if a user with that username already exists
     */
    public void createAdminUser(String username, String password) {
        if (userRepo.get(username) != null) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        long now = System.currentTimeMillis();
        VorkUser admin = new VorkUser(
                username,
                passwordEncoder.encode(password),
                UserRole.ADMIN.name(),
                true,
                now,
                now);
        userRepo.save(admin);
        setupComplete = true;
        log.info("Admin user created during setup: [username={}]", username);
    }
}
