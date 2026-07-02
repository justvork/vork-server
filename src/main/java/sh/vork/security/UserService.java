package sh.vork.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseRepository;

/**
 * Canonical user lookup service used by runtime components that need the
 * persisted {@link VorkUser} object.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final DatabaseRepository<VorkUser> userRepository;

    public UserService(DatabaseRepository<VorkUser> userRepository) {
        this.userRepository = userRepository;
    }

    public VorkUser getRequiredUser(String username) {
        log.debug("ENTER getRequiredUser: username={}", username);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank.");
        }

        VorkUser user = userRepository.get(username);
        if (user == null) {
            throw new IllegalStateException("User not found: " + username);
        }
        log.debug("EXIT getRequiredUser: username={}, enabled={}, role={}",
                user.uuid(), user.isEnabled(), user.role());
        return user;
    }

    public VorkUser getRequiredEnabledUser(String username) {
        log.debug("ENTER getRequiredEnabledUser: username={}", username);
        VorkUser user = getRequiredUser(username);
        if (!user.isEnabled()) {
            throw new IllegalStateException("User is disabled: " + username);
        }
        log.debug("EXIT getRequiredEnabledUser: username={}", user.uuid());
        return user;
    }
}
