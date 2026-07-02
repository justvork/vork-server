package sh.vork.security;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseRepository;

/**
 * Backfills legacy user records to canonical role values and explicit enabled state.
 */
@Service
public class UserNormalizationService {

    private static final Logger log = LoggerFactory.getLogger(UserNormalizationService.class);

    private final DatabaseRepository<VorkUser> userRepository;

    public UserNormalizationService(DatabaseRepository<VorkUser> userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    void normalizeLegacyUsers() {
        log.debug("ENTER normalizeLegacyUsers");
        List<VorkUser> updates = new ArrayList<>();
        try (var users = userRepository.list(0, Integer.MAX_VALUE)) {
            users.forEach(user -> {
                UserRole normalizedRole = UserRole.fromStoredValue(user.role());
                boolean normalizedEnabled = user.isEnabled();
                boolean roleChanged = !normalizedRole.name().equals(user.role());
                boolean enabledChanged = user.enabled() == null;
                if (roleChanged || enabledChanged) {
                    updates.add(new VorkUser(
                            user.uuid(),
                            user.passwordHash(),
                            normalizedRole.name(),
                            normalizedEnabled,
                            user.createdAt(),
                            user.updatedAt()));
                }
            });
        }

        for (VorkUser updated : updates) {
            userRepository.save(updated);
        }
        log.info("EXIT normalizeLegacyUsers: updatedUsers={}", updates.size());
    }
}
