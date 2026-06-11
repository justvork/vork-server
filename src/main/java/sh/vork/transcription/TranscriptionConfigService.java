package sh.vork.transcription;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseRepository;

/**
 * CRUD service for the singleton {@link TranscriptionConfig} and resolution of the
 * active {@link TranscriptionProvider}.
 *
 * <p>The active configuration is stored under the fixed primary key {@code "current"}.
 * {@link #getCurrent()} returns {@code null} when no transcription provider has been
 * configured.
 */
@Service
public class TranscriptionConfigService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionConfigService.class);
    private static final String CONFIG_KEY = "current";

    private final DatabaseRepository<TranscriptionConfig> configRepo;
    private final Collection<TranscriptionProvider> providers;

    public TranscriptionConfigService(DatabaseRepository<TranscriptionConfig> configRepo,
                                      Collection<TranscriptionProvider> providers) {
        this.configRepo = configRepo;
        this.providers  = providers;
    }

    /**
     * Returns the current transcription configuration, or {@code null} if none is set.
     */
    public TranscriptionConfig getCurrent() {
        return configRepo.get(CONFIG_KEY);
    }

    /**
     * Saves (upserts) the transcription configuration.
     *
     * @param providerKey the {@link TranscriptionProvider#providerKey()} to activate
     * @param settings    provider-specific settings (may be empty for AI-backed providers)
     * @return the saved config
     * @throws IllegalArgumentException if no registered provider matches {@code providerKey}
     */
    public TranscriptionConfig save(String providerKey, Map<String, String> settings) {
        boolean known = providers.stream().anyMatch(p -> p.providerKey().equals(providerKey));
        if (!known) {
            throw new IllegalArgumentException("Unknown transcription provider: " + providerKey);
        }
        TranscriptionConfig config = new TranscriptionConfig(
                CONFIG_KEY, providerKey, settings != null ? settings : Map.of());
        configRepo.save(config);
        log.info("Transcription provider set [providerKey={}]", providerKey);
        return config;
    }

    /**
     * Clears the transcription configuration (no transcription provider will be active).
     */
    public void clear() {
        configRepo.delete(CONFIG_KEY);
        log.info("Transcription provider configuration cleared");
    }

    /**
     * Returns the active {@link TranscriptionProvider}, or {@code null} if none is configured.
     */
    public TranscriptionProvider resolveProvider() {
        TranscriptionConfig cfg = getCurrent();
        if (cfg == null) {
            return null;
        }
        return providers.stream()
                .filter(p -> p.providerKey().equals(cfg.providerKey()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns all registered {@link TranscriptionProvider} beans.
     */
    public List<TranscriptionProvider> listAvailable() {
        return List.copyOf(providers);
    }
}
