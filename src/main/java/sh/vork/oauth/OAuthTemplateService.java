package sh.vork.oauth;

import java.net.URI;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

/**
 * CRUD service for shared OAuth templates.
 */
@Service
public class OAuthTemplateService {

    private static final Logger log = LoggerFactory.getLogger(OAuthTemplateService.class);

    private final DatabaseRepository<OAuthTemplateEntity> templateRepository;

    public OAuthTemplateService(RepositoryFactory factory) {
        this.templateRepository = factory.create(OAuthTemplateEntity.class);
    }

    public List<OAuthTemplate> listTemplates() {
        log.debug("ENTER listTemplates");
        try (var stream = templateRepository.list(0, Integer.MAX_VALUE)) {
            List<OAuthTemplate> result = stream
                    .sorted(Comparator.comparing(t -> t.name() == null ? "" : t.name(), String.CASE_INSENSITIVE_ORDER))
                    .map(this::toModel)
                    .toList();
            log.debug("EXIT listTemplates: count={}", result.size());
            return result;
        }
    }

    public OAuthTemplateExportPackage exportTemplate(UUID id) {
        log.debug("ENTER exportTemplate: id={}", id);
        OAuthTemplate template = getTemplate(id);
        if (template == null) {
            log.debug("EXIT exportTemplate: not found [id={}]", id);
            return null;
        }
        OAuthTemplateExportPackage pkg = new OAuthTemplateExportPackage(
                "vorkOAuthTemplateExport",
                1,
                List.of(template));
        log.debug("EXIT exportTemplate: exported id={}", id);
        return pkg;
    }

    public OAuthTemplateImportResult importTemplates(OAuthTemplateExportPackage pkg) {
        log.debug("ENTER importTemplates");
        if (pkg == null) {
            return new OAuthTemplateImportResult("error", "Import payload is required.", 0, 0);
        }
        if (!"vorkOAuthTemplateExport".equals(pkg.vorkOAuthTemplateExport())) {
            return new OAuthTemplateImportResult("error", "Not a valid Vork OAuth template export package.", 0, 0);
        }
        if (pkg.templates() == null || pkg.templates().isEmpty()) {
            return new OAuthTemplateImportResult("error", "Export package does not contain any templates.", 0, 0);
        }

        int created = 0;
        int updated = 0;

        for (OAuthTemplate template : pkg.templates()) {
            OAuthTemplate normalized = normalizeAndValidate(template);
            UUID id = normalized.id() == null ? UUID.randomUUID() : normalized.id();

            OAuthTemplateEntity existing = templateRepository.get(id.toString());
            if (existing == null) {
                long now = System.currentTimeMillis();
                OAuthTemplateEntity createdEntity = new OAuthTemplateEntity(
                        id.toString(),
                        normalized.name(),
                    normalized.clientName(),
                        normalized.description(),
                        normalized.authorizeEndpoint().toString(),
                        normalized.tokenEndpoint().toString(),
                        List.copyOf(normalized.scopes()),
                        Map.copyOf(normalized.authorizationParameters()),
                        now,
                        now);
                templateRepository.save(createdEntity);
                created++;
            } else {
                OAuthTemplateEntity updatedEntity = new OAuthTemplateEntity(
                        existing.uuid(),
                        normalized.name(),
                    normalized.clientName(),
                        normalized.description(),
                        normalized.authorizeEndpoint().toString(),
                        normalized.tokenEndpoint().toString(),
                        List.copyOf(normalized.scopes()),
                        Map.copyOf(normalized.authorizationParameters()),
                        existing.createdAt(),
                        System.currentTimeMillis());
                templateRepository.save(updatedEntity);
                updated++;
            }
        }

        log.info("OAuth template import completed [created={}, updated={}]", created, updated);
        log.debug("EXIT importTemplates");
        return new OAuthTemplateImportResult(
                "ok",
                "OAuth templates imported successfully.",
                created,
                updated);
    }

    public OAuthTemplate getTemplate(UUID id) {
        log.debug("ENTER getTemplate: id={}", id);
        if (id == null) {
            return null;
        }
        OAuthTemplateEntity entity = templateRepository.get(id.toString());
        OAuthTemplate result = entity == null ? null : toModel(entity);
        log.debug("EXIT getTemplate: found={}", result != null);
        return result;
    }

    public OAuthTemplate createTemplate(OAuthTemplate template) {
        log.debug("ENTER createTemplate: name={}", template == null ? "null" : template.name());
        OAuthTemplate normalized = normalizeAndValidate(template);
        UUID id = normalized.id() == null ? UUID.randomUUID() : normalized.id();
        long now = System.currentTimeMillis();

        OAuthTemplateEntity entity = new OAuthTemplateEntity(
                id.toString(),
                normalized.name(),
            normalized.clientName(),
                normalized.description(),
                normalized.authorizeEndpoint().toString(),
                normalized.tokenEndpoint().toString(),
                List.copyOf(normalized.scopes()),
                Map.copyOf(normalized.authorizationParameters()),
                now,
                now);

        templateRepository.save(entity);
        OAuthTemplate result = toModel(entity);
        log.info("OAuth template created [id={}, name={}]", result.id(), result.name());
        log.debug("EXIT createTemplate: id={}", result.id());
        return result;
    }

    public OAuthTemplate updateTemplate(UUID id, OAuthTemplate template) {
        log.debug("ENTER updateTemplate: id={}", id);
        if (id == null) {
            return null;
        }

        OAuthTemplateEntity existing = templateRepository.get(id.toString());
        if (existing == null) {
            log.debug("EXIT updateTemplate: template not found [id={}]", id);
            return null;
        }

        OAuthTemplate normalized = normalizeAndValidate(template);
        long now = System.currentTimeMillis();

        OAuthTemplateEntity updated = new OAuthTemplateEntity(
                existing.uuid(),
                normalized.name(),
            normalized.clientName(),
                normalized.description(),
                normalized.authorizeEndpoint().toString(),
                normalized.tokenEndpoint().toString(),
                List.copyOf(normalized.scopes()),
                Map.copyOf(normalized.authorizationParameters()),
                existing.createdAt(),
                now);

        templateRepository.save(updated);
        OAuthTemplate result = toModel(updated);
        log.info("OAuth template updated [id={}, name={}]", result.id(), result.name());
        log.debug("EXIT updateTemplate: id={}", result.id());
        return result;
    }

    public boolean deleteTemplate(UUID id) {
        log.debug("ENTER deleteTemplate: id={}", id);
        if (id == null) {
            return false;
        }
        OAuthTemplateEntity existing = templateRepository.get(id.toString());
        if (existing == null) {
            log.debug("EXIT deleteTemplate: template not found [id={}]", id);
            return false;
        }
        templateRepository.delete(id.toString());
        log.info("OAuth template deleted [id={}, name={}]", id, existing.name());
        log.debug("EXIT deleteTemplate: id={}", id);
        return true;
    }

    private OAuthTemplate normalizeAndValidate(OAuthTemplate template) {
        if (template == null) {
            throw new IllegalArgumentException("Template payload is required.");
        }

        String name = template.name() == null ? "" : template.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Template name is required.");
        }

        String requestedClientName = template.clientName() == null ? "" : template.clientName().trim();
        String clientName = OAuthClientService.normalizeClientName(requestedClientName.isBlank() ? name : requestedClientName);
        if (clientName.isBlank()) {
            throw new IllegalArgumentException("Template clientName is required.");
        }

        if (template.authorizeEndpoint() == null) {
            throw new IllegalArgumentException("authorizeEndpoint is required.");
        }
        if (template.tokenEndpoint() == null) {
            throw new IllegalArgumentException("tokenEndpoint is required.");
        }

        String description = template.description() == null ? "" : template.description().trim();
        List<String> scopes = template.scopes() == null ? List.of() : template.scopes().stream()
                .map(scope -> scope == null ? "" : scope.trim())
                .filter(scope -> !scope.isBlank())
                .toList();

        Map<String, String> authorizationParameters = sanitizeParams(template.authorizationParameters());

        return new OAuthTemplate(
                template.id(),
                name,
            clientName,
                description,
                template.authorizeEndpoint(),
                template.tokenEndpoint(),
                scopes,
                authorizationParameters);
    }

    private Map<String, String> sanitizeParams(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            out.put(key, value);
        }
        return Map.copyOf(out);
    }

    private OAuthTemplate toModel(OAuthTemplateEntity entity) {
        return new OAuthTemplate(
                UUID.fromString(entity.uuid()),
                entity.name(),
            entity.clientName(),
                entity.description(),
                URI.create(entity.authorizeEndpoint()),
                URI.create(entity.tokenEndpoint()),
                entity.scopes(),
                entity.authorizationParameters());
    }

        public record OAuthTemplateExportPackage(
            String vorkOAuthTemplateExport,
            int version,
            List<OAuthTemplate> templates
        ) {}

        public record OAuthTemplateImportResult(
            String status,
            String message,
            int created,
            int updated
        ) {}
}
