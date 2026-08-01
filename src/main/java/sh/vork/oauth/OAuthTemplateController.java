package sh.vork.oauth;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sh.vork.ai.function.OAuthConnectRequest;
import sh.vork.web.RequestOriginContext;

/**
 * REST API for OAuth template management.
 */
@RestController
@RequestMapping("/api/oauth-templates")
@PreAuthorize("isAuthenticated()")
public class OAuthTemplateController {

    private static final Logger log = LoggerFactory.getLogger(OAuthTemplateController.class);

    private final OAuthTemplateService templateService;
    private final OAuthClientService oauthClientService;

    public OAuthTemplateController(OAuthTemplateService templateService,
                                   OAuthClientService oauthClientService) {
        this.templateService = templateService;
        this.oauthClientService = oauthClientService;
    }

    @GetMapping
    public ResponseEntity<?> listTemplates() {
        log.debug("ENTER listTemplates");
        List<OAuthTemplate> templates = templateService.listTemplates();
        log.debug("EXIT listTemplates: count={}", templates.size());
        return ResponseEntity.ok(templates);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTemplate(@PathVariable UUID id) {
        log.debug("ENTER getTemplate: id={}", id);
        OAuthTemplate template = templateService.getTemplate(id);
        if (template == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(template);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createTemplate(@RequestBody OAuthTemplate template) {
        log.debug("ENTER createTemplate: name={}", template == null ? "null" : template.name());
        try {
            OAuthTemplate created = templateService.createTemplate(template);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateTemplate(@PathVariable UUID id,
                                            @RequestBody OAuthTemplate template) {
        log.debug("ENTER updateTemplate: id={}", id);
        try {
            OAuthTemplate updated = templateService.updateTemplate(id, template);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteTemplate(@PathVariable UUID id) {
        log.debug("ENTER deleteTemplate: id={}", id);
        boolean deleted = templateService.deleteTemplate(id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> exportTemplate(@PathVariable UUID id) {
        log.debug("ENTER exportTemplate: id={}", id);
        OAuthTemplateService.OAuthTemplateExportPackage pkg = templateService.exportTemplate(id);
        if (pkg == null || pkg.templates() == null || pkg.templates().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        OAuthTemplate template = pkg.templates().getFirst();
        String safeName = template.name() == null ? "oauth-template" : template.name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "oauth-template-" + safeName + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pkg);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> importTemplates(@RequestBody OAuthTemplateService.OAuthTemplateExportPackage pkg) {
        log.debug("ENTER importTemplates");
        OAuthTemplateService.OAuthTemplateImportResult result = templateService.importTemplates(pkg);
        if (!"ok".equals(result.status())) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/connect")
    public ResponseEntity<?> connectTemplate(@PathVariable UUID id,
                                             @RequestBody OAuthTemplateConnectRequest request) {
        log.debug("ENTER connectTemplate: id={}", id);
        String username = resolveUsername();
        if (username == null) {
            log.warn("connectTemplate rejected: unauthenticated request [templateId={}]", id);
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Authenticated user is required"));
        }

        OAuthTemplate template = templateService.getTemplate(id);
        if (template == null) {
            log.warn("connectTemplate rejected: template not found [id={}]", id);
            return ResponseEntity.notFound().build();
        }

        String profileName = normalizeProfileName(request == null ? null : request.profileName());
        if (profileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Profile name is required."));
        }

        Map<String, Object> profileDiscovery = oauthClientService.discoverProfiles(username, template.clientName());
        Object discoveredStatus = profileDiscovery.get("status");
        if ("ok".equals(discoveredStatus)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> profiles = (List<Map<String, Object>>) profileDiscovery.get("profiles");
            if (profiles != null) {
                boolean duplicate = profiles.stream()
                        .map(p -> String.valueOf(p.getOrDefault("name", "")))
                        .anyMatch(existing -> profileName.equals(existing));
                if (duplicate) {
                    log.warn("connectTemplate rejected: duplicate profile [user={}, clientName={}, profileName={}]",
                            username, template.clientName(), profileName);
                    return ResponseEntity.badRequest().body(Map.of(
                            "status", "error",
                            "message", "An OAuth client profile with this name already exists for clientName '" + template.clientName() + "'. Use a different profile name."));
                }
            }
        }

        String redirectUri = firstNonBlank(
                request == null ? null : request.redirectUri(),
                suggestedRedirectUriFromCurrentRequest());
        if (redirectUri == null || redirectUri.isBlank()) {
            log.warn("connectTemplate rejected: redirect URI unavailable [user={}, templateId={}]", username, id);
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Redirect URI is required."));
        }

        OAuthConnectRequest connectRequest = new OAuthConnectRequest(
                template.clientName(),
                profileName,
                template.authorizeEndpoint() == null ? null : template.authorizeEndpoint().toString(),
                template.tokenEndpoint() == null ? null : template.tokenEndpoint().toString(),
                request == null ? null : request.clientId(),
                request == null ? null : request.clientSecret(),
                redirectUri,
                template.scopes(),
                template.authorizationParameters(),
            true,
            request == null ? null : request.returnPath());

        Map<String, Object> result = oauthClientService.connectOrEnsure(username, connectRequest);
        String status = String.valueOf(result.getOrDefault("status", ""));
        if ("error".equals(status)) {
            log.warn("connectTemplate failed [user={}, clientName={}, profileName={}]: {}",
                    username, template.clientName(), profileName, result.get("message"));
            return ResponseEntity.badRequest().body(result);
        }
        log.debug("EXIT connectTemplate: status={}, clientName={}, profileName={}",
                status, template.clientName(), profileName);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/connect-defaults")
    public ResponseEntity<?> connectDefaults() {
        log.debug("ENTER connectDefaults");
        String redirectUri = suggestedRedirectUriFromCurrentRequest();
        log.debug("EXIT connectDefaults: redirectUriPresent={}", redirectUri != null && !redirectUri.isBlank());
        return ResponseEntity.ok(Map.of("redirectUri", redirectUri == null ? "" : redirectUri));
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    public record OAuthTemplateConnectRequest(
            String profileName,
            String clientId,
            String clientSecret,
            String redirectUri,
            String returnPath
    ) {}

    private static String normalizeProfileName(String raw) {
        String normalized = OAuthClientService.normalizeClientName(raw);
        return normalized == null || normalized.isBlank() ? "default" : normalized;
    }

    private static String suggestedRedirectUriFromCurrentRequest() {
        String baseUrl = RequestOriginContext.resolveBaseUrlFromCurrentRequest();
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        return baseUrl + "/api/oauth/callback";
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleMalformedImportJson(HttpMessageNotReadableException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage();

        log.warn("OAuth template import JSON parse failure: {}", detail, ex);

        return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Invalid JSON payload for OAuth template import.",
                "detail", detail
        ));
    }
}
