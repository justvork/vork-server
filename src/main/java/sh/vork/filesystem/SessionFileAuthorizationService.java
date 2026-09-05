package sh.vork.filesystem;

import org.springframework.stereotype.Service;
import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;

import java.security.Principal;

@Service
public class SessionFileAuthorizationService {

    private final DatabaseRepository<AiSession> aiSessionRepository;

    public SessionFileAuthorizationService(DatabaseRepository<AiSession> aiSessionRepository) {
        this.aiSessionRepository = aiSessionRepository;
    }

    public boolean isAuthorized(FileArea area, String sessionUuid, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return false;
        }
        if (area == FileArea.SHARED) {
            return true;
        }
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return false;
        }

        AiSession session = aiSessionRepository.get(sessionUuid);
        return session != null && principal.getName().equals(session.username());
    }
}
