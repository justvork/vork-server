package sh.vork.transcription.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.transcription.TranscriptionConfig;
import sh.vork.transcription.TranscriptionConfigService;
import sh.vork.transcription.TranscriptionProvider;

/**
 * REST API for managing the audio transcription provider configuration.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/transcription/providers} — list all available providers</li>
 *   <li>{@code GET  /api/transcription/config}    — get current config</li>
 *   <li>{@code PUT  /api/transcription/config}    — set active provider</li>
 *   <li>{@code DELETE /api/transcription/config}  — clear configuration</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/transcription")
public class TranscriptionController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionController.class);

    private final TranscriptionConfigService configService;

    public TranscriptionController(TranscriptionConfigService configService) {
        this.configService = configService;
    }

    /**
     * Lists all registered {@link TranscriptionProvider} beans with metadata the UI
     * needs to render provider cards and checkboxes.
     *
     * <p>When {@code backedByAiProvider} is non-null the UI should render a checkbox
     * on the matching AI provider card rather than a standalone configuration form.
     */
    @GetMapping("/providers")
    public List<ProviderView> listProviders() {
        log.debug("ENTER listProviders");
        return configService.listAvailable().stream()
                .map(p -> new ProviderView(
                        p.providerKey(),
                        p.displayName(),
                        p.backedByAiProvider(),
                        !p.getSettingDefinitions().isEmpty()))
                .toList();
    }

    /**
     * Returns the current transcription configuration.
     *
     * <p>Response contains {@code configured: false} when no provider has been set.
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        log.debug("ENTER getConfig");
        TranscriptionConfig cfg = configService.getCurrent();
        if (cfg == null) {
            return ResponseEntity.ok(Map.of("configured", false));
        }
        return ResponseEntity.ok(Map.of(
                "configured",   true,
                "providerKey",  cfg.providerKey()));
    }

    /**
     * Sets (upserts) the active transcription provider.
     *
     * <p>For AI-backed providers (Gemini, OpenAI, Groq) {@code settings} should be
     * omitted or empty — credentials are read from the existing AI provider config.
     */
    @PutMapping("/config")
    public ResponseEntity<?> saveConfig(@RequestBody TranscriptionConfigRequest request) {
        log.debug("ENTER saveConfig: [providerKey={}]", request.providerKey());
        if (request.providerKey() == null || request.providerKey().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "ERROR", "message", "providerKey is required"));
        }
        try {
            configService.save(request.providerKey(),
                    request.settings() != null ? request.settings() : Map.of());
            log.info("Transcription provider configured [providerKey={}]", request.providerKey());
            return ResponseEntity.ok(Map.of("status", "OK", "providerKey", request.providerKey()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * Clears the transcription configuration.  After this call no transcription
     * provider is active and incoming audio messages cannot be transcribed.
     */
    @DeleteMapping("/config")
    public ResponseEntity<?> clearConfig() {
        log.debug("ENTER clearConfig");
        configService.clear();
        log.info("Transcription provider configuration cleared");
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Summary of a registered transcription provider for the settings UI.
     *
     * @param providerKey        stable identifier (e.g. {@code "groq"})
     * @param displayName        human-readable name (e.g. {@code "Groq Whisper"})
     * @param backedByAiProvider the {@code AiProvider} enum name this provider uses
     *                           for credentials, or {@code null} for standalone providers
     * @param requiresOwnConfig  {@code true} when the provider needs its own settings form
     */
    public record ProviderView(
            String  providerKey,
            String  displayName,
            String  backedByAiProvider,
            boolean requiresOwnConfig
    ) {}

    record TranscriptionConfigRequest(String providerKey, Map<String, String> settings) {}
}
