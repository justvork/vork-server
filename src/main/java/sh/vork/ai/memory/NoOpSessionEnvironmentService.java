package sh.vork.ai.memory;

import java.util.Map;

/**
 * No-op fallback for non-MongoDB backends (redis, nitrite).
 * Session environment variables are not persisted in these modes.
 * Registered as a Spring bean by {@link SessionEnvironmentConfig}.
 */
public class NoOpSessionEnvironmentService implements SessionEnvironmentService {

    @Override
    public void setEnv(String sessionUuid, String key, String value) {
        // no-op — no environment persistence for non-Mongo backends
    }

    @Override
    public Map<String, String> getEnv(String sessionUuid) {
        return Map.of();
    }
}
