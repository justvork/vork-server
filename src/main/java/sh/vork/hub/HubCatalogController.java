package sh.vork.hub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hub")
@PreAuthorize("isAuthenticated()")
public class HubCatalogController {

    private static final Logger log = LoggerFactory.getLogger(HubCatalogController.class);

    private final HubCatalogService hubCatalogService;

    public HubCatalogController(HubCatalogService hubCatalogService) {
        this.hubCatalogService = hubCatalogService;
    }

    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(@RequestParam(name = "repositoryName", required = false) String repositoryName,
                                     @RequestParam(name = "type", required = false) String type,
                                     @RequestParam(name = "q", required = false) String query) {
        log.debug("ENTER catalog: repositoryName={}, type={}, q={}", repositoryName, type, query);
        try {
            List<HubCatalogService.HubCatalogItem> items = hubCatalogService.listCatalogItems(repositoryName, type, query);
            return ResponseEntity.ok(items);
        } catch (RuntimeException ex) {
            log.warn("catalog failed: {}", ex.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "status", "error",
                    "message", "Failed to load Hub catalog.",
                    "detail", ex.getMessage()));
        }
    }

    @GetMapping("/artifact")
    public ResponseEntity<?> artifact(@RequestParam(name = "repositoryName", required = false) String repositoryName,
                                      @RequestParam(name = "path") String path) {
        log.debug("ENTER artifact: repositoryName={}, path={}", repositoryName, path);
        try {
            HubCatalogService.ArtifactPayload payload = hubCatalogService.loadArtifact(repositoryName, path);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(payload.mediaType()))
                    .body(payload.bytes());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("artifact failed: {}", ex.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "status", "error",
                    "message", "Failed to load repository artifact.",
                    "detail", ex.getMessage()));
        }
    }

    @PostMapping("/install/prepare")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> prepareInstall(@RequestBody InstallPrepareRequest request) {
        log.debug("ENTER prepareInstall: repositoryName={}, type={}, installPath={}",
                request == null ? null : request.repositoryName(),
                request == null ? null : request.type(),
                request == null ? null : request.installPath());

        if (request == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Request body is required."));
        }

        try {
            HubCatalogService.InstallPackage installPackage = hubCatalogService.prepareInstallPackage(
                    request.repositoryName(), request.type(), request.installPath());
            return ResponseEntity.ok(installPackage);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("prepareInstall failed: {}", ex.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "status", "error",
                    "message", "Failed to prepare install package.",
                    "detail", ex.getMessage()));
        }
    }

    public record InstallPrepareRequest(String repositoryName, String type, String installPath) {
    }
}
