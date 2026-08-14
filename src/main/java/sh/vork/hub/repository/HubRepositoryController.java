package sh.vork.hub.repository;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only API for Hub repository source visibility.
 */
@RestController
@RequestMapping("/api/hub/repositories")
@PreAuthorize("isAuthenticated()")
public class HubRepositoryController {

    private static final Logger log = LoggerFactory.getLogger(HubRepositoryController.class);

    private final HubRepositoryRegistryService hubRepositoryRegistryService;

    public HubRepositoryController(HubRepositoryRegistryService hubRepositoryRegistryService) {
        this.hubRepositoryRegistryService = hubRepositoryRegistryService;
    }

    @GetMapping
    public ResponseEntity<?> listRepositories() {
        log.debug("ENTER listRepositories");
        try {
            List<HubRepositorySummary> repositories = hubRepositoryRegistryService.resolveRepositories().stream()
                    .map(repo -> new HubRepositorySummary(
                            repo.name(),
                            repo.baseUrl() == null ? "" : repo.baseUrl().toString(),
                            repo.readOnly(),
                            repo.available(),
                            repo.message()))
                    .toList();
            log.debug("EXIT listRepositories: count={}", repositories.size());
            return ResponseEntity.ok(repositories);
        } catch (RuntimeException ex) {
            log.warn("listRepositories failed: {}", ex.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "status", "error",
                    "message", "Failed to resolve Hub repositories.",
                    "detail", ex.getMessage()));
        }
    }

    public record HubRepositorySummary(
            String name,
            String baseUrl,
            boolean readOnly,
            boolean available,
            String message
    ) {
    }
}
