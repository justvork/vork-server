package sh.vork.skill;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fetches and caches the canonical skill-category list from the vork-skills
 * GitHub repository.  The list is refreshed every 24 hours via a scheduled
 * task; on first call it is loaded eagerly.
 *
 * <p>The remote JSON must be either a JSON array of strings
 * ({@code ["Productivity", "AI", ...]}) or an array of objects each carrying
 * a {@code "name"} or {@code "label"} field.  Any other shape returns an
 * empty list and logs a warning.
 *
 * <p>On any network or parse error the previous cached list is retained
 * (or an empty list on the very first attempt) so the application stays
 * functional.
 */
@Service
public class SkillCategoryService {

    private static final Logger log = LoggerFactory.getLogger(SkillCategoryService.class);

    static final String CATEGORIES_URL =
            "https://raw.githubusercontent.com/vork-ai/vork-skills/main/categories.json";

    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1_000; // 24 hours
        private static final int STARTUP_REFRESH_ATTEMPTS = 3;

    private final RestClient    restClient;
    private final ObjectMapper  objectMapper;

    /** Cached category names — thread-safe; never null after construction. */
    private final AtomicReference<List<String>> cache = new AtomicReference<>(List.of());
    @SuppressWarnings("unused")
    private volatile long lastFetchedAt = 0;

    public SkillCategoryService(RestClient.Builder restClientBuilder,
                                ObjectMapper objectMapper) {
        this.restClient   = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached category list.  Always returns an unmodifiable list;
     * may be empty if no data has been loaded yet.
     */
    public List<String> getCategories() {
        return cache.get();
    }

    // ── Scheduled refresh ─────────────────────────────────────────────────────

    /** Refreshes the category list every 24 hours. */
    @Scheduled(fixedDelay = CACHE_TTL_MS)
    public void refresh() {
        refreshOnce();
    }

    /**
     * Forces a refresh whenever the app starts/restarts, with a small retry
     * window in case outbound networking is briefly unavailable during boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        log.debug("ENTER refreshOnStartup");
        for (int attempt = 1; attempt <= STARTUP_REFRESH_ATTEMPTS; attempt++) {
            if (refreshOnce()) {
                log.debug("EXIT refreshOnStartup: success on attempt {}", attempt);
                return;
            }
            if (attempt < STARTUP_REFRESH_ATTEMPTS) {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Startup category refresh interrupted");
                    return;
                }
            }
        }
        log.warn("Startup category refresh failed after {} attempts", STARTUP_REFRESH_ATTEMPTS);
    }

    private boolean refreshOnce() {
        log.debug("ENTER refresh: fetching categories from {}", CATEGORIES_URL);
        try {
            String body = restClient.get()
                    .uri(CATEGORIES_URL)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                log.warn("Empty response from categories URL — retaining previous cache");
                return false;
            }

            List<String> parsed = parseCategories(body);
            if (parsed.isEmpty()) {
                log.warn("Parsed zero categories from response — retaining previous cache");
                return false;
            }

            cache.set(Collections.unmodifiableList(parsed));
            lastFetchedAt = System.currentTimeMillis();
            log.info("Skill categories refreshed [count={}, source={}]", parsed.size(), CATEGORIES_URL);
            return true;

        } catch (Exception e) {
            log.warn("Failed to refresh skill categories [url={}]: {} — retaining previous cache",
                    CATEGORIES_URL, e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses the JSON body into a list of category name strings.
     * Handles two formats:
     * <ul>
     *   <li>Array of strings: {@code ["Productivity", "AI"]}</li>
     *   <li>Array of objects: {@code [{"name": "Productivity"}, ...]}</li>
     * </ul>
     */
    private List<String> parseCategories(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            log.warn("Categories JSON is not an array — got: {}", root.getNodeType());
            return List.of();
        }

        // Check first element to determine format
        if (root.isEmpty()) return List.of();

        JsonNode first = root.get(0);
        if (first.isTextual()) {
            // Simple string array
            return objectMapper.convertValue(root, new TypeReference<List<String>>() {});
        }

        // Object array — try "name", then "label", then "id"
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode node : root) {
            String value = extractTextField(node, "name", "label", "id");
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String extractTextField(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode f = node.get(field);
            if (f != null && f.isTextual() && !f.asText().isBlank()) {
                return f.asText();
            }
        }
        return null;
    }
}
