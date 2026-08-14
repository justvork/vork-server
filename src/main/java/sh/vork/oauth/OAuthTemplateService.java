package sh.vork.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sh.vork.hub.repository.HubRepositoryDefinition;
import sh.vork.hub.repository.HubRepositoryRegistryService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

/**
 * CRUD service for shared OAuth templates.
 */
@Service
public class OAuthTemplateService {

    private static final Logger log = LoggerFactory.getLogger(OAuthTemplateService.class);
    private static final URI DEFAULT_SYNC_ROOT =
            URI.create("https://raw.githubusercontent.com/justvork/vork-central/main");

    private final DatabaseRepository<OAuthTemplateEntity> templateRepository;
    private final HubRepositoryRegistryService hubRepositoryRegistryService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public OAuthTemplateService(RepositoryFactory factory, HubRepositoryRegistryService hubRepositoryRegistryService) {
        this.templateRepository = factory.create(OAuthTemplateEntity.class);
        this.hubRepositoryRegistryService = hubRepositoryRegistryService;
    }

    public OAuthTemplateService(RepositoryFactory factory) {
        this.templateRepository = factory.create(OAuthTemplateEntity.class);
        this.hubRepositoryRegistryService = null;
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
            ensureUniqueClientName(normalized.clientName(), id);

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
                        normalized.artifactStatus(),
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
                        normalized.artifactStatus(),
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
        ensureUniqueClientName(normalized.clientName(), null);
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
                ArtifactStatus.SNAPSHOT,
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
        if (existing.artifactStatus() != ArtifactStatus.SNAPSHOT
                && existing.artifactStatus() != ArtifactStatus.REJECTED) {
            throw new IllegalArgumentException("Only SNAPSHOT or REJECTED OAuth templates can be edited.");
        }

        OAuthTemplate normalized = normalizeAndValidate(template);
        ensureUniqueClientName(normalized.clientName(), id);
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
                existing.artifactStatus(),
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
        if (existing.artifactStatus() != ArtifactStatus.SNAPSHOT
                && existing.artifactStatus() != ArtifactStatus.REJECTED) {
            throw new IllegalArgumentException("Only SNAPSHOT or REJECTED OAuth templates can be deleted.");
        }
        templateRepository.delete(id.toString());
        log.info("OAuth template deleted [id={}, name={}]", id, existing.name());
        log.debug("EXIT deleteTemplate: id={}", id);
        return true;
    }

    public OAuthTemplate markSubmitted(UUID id) {
        log.debug("ENTER markSubmitted: id={}", id);
        OAuthTemplateEntity existing = id == null ? null : templateRepository.get(id.toString());
        if (existing == null) {
            log.debug("EXIT markSubmitted: template not found [id={}]", id);
            return null;
        }
        OAuthTemplateEntity updated = new OAuthTemplateEntity(
                existing.uuid(),
                existing.name(),
                existing.clientName(),
                existing.description(),
                existing.authorizeEndpoint(),
                existing.tokenEndpoint(),
                existing.scopes(),
                existing.authorizationParameters(),
                ArtifactStatus.SUBMITTED,
                existing.createdAt(),
                System.currentTimeMillis());
        templateRepository.save(updated);
        OAuthTemplate result = toModel(updated);
        log.debug("EXIT markSubmitted: id={}, status={}", id, result.artifactStatus());
        return result;
    }

    public OAuthTemplateSyncResult synchronizeFromMain() {
        return synchronizeFromRepository(null);
    }

    public OAuthTemplateSyncResult synchronizeFromRepository(String repositoryName) {
        log.debug("ENTER synchronizeFromMain");
        URI syncRoot = resolveSyncRoot(repositoryName);
        List<String> fileNames = listMainTemplateFiles(syncRoot);
        if (fileNames.isEmpty()) {
            log.info("OAuth template sync found no templates in repository [name={}, root={}]", repositoryName, syncRoot);
            return new OAuthTemplateSyncResult("ok", "No templates found in main branch.", 0, 0, 0, 0);
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (String fileName : fileNames) {
            String clientName = fileName.substring(0, fileName.length() - ".json".length()).trim();
            if (clientName.isBlank()) {
                skipped++;
                continue;
            }

            OAuthTemplate remote = fetchTemplateFromMain(syncRoot, fileName);
            if (remote == null) {
                skipped++;
                continue;
            }

            OAuthTemplate normalized;
            try {
                normalized = normalizeAndValidate(new OAuthTemplate(
                        remote.id(),
                        remote.name(),
                        clientName,
                        remote.description(),
                        remote.authorizeEndpoint(),
                        remote.tokenEndpoint(),
                        remote.scopes(),
                        remote.authorizationParameters(),
                        ArtifactStatus.PUBLISHED));
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping invalid OAuth template from main [file={}]: {}", fileName, ex.getMessage());
                skipped++;
                continue;
            }

            OAuthTemplateEntity existing = findEntityByClientName(clientName);
            if (existing == null) {
                long now = System.currentTimeMillis();
                OAuthTemplateEntity createdEntity = new OAuthTemplateEntity(
                        UUID.randomUUID().toString(),
                        normalized.name(),
                        normalized.clientName(),
                        normalized.description(),
                        normalized.authorizeEndpoint().toString(),
                        normalized.tokenEndpoint().toString(),
                        List.copyOf(normalized.scopes()),
                        Map.copyOf(normalized.authorizationParameters()),
                        ArtifactStatus.PUBLISHED,
                        now,
                        now);
                templateRepository.save(createdEntity);
                created++;
                continue;
            }

            OAuthTemplateEntity updatedEntity = new OAuthTemplateEntity(
                    existing.uuid(),
                    normalized.name(),
                    normalized.clientName(),
                    normalized.description(),
                    normalized.authorizeEndpoint().toString(),
                    normalized.tokenEndpoint().toString(),
                    List.copyOf(normalized.scopes()),
                    Map.copyOf(normalized.authorizationParameters()),
                    ArtifactStatus.PUBLISHED,
                    existing.createdAt(),
                    System.currentTimeMillis());
            templateRepository.save(updatedEntity);
            updated++;
        }

        log.info("OAuth template sync completed [repositoryName={}, root={}, created={}, updated={}, skipped={}]",
            repositoryName, syncRoot, created, updated, skipped);
        log.debug("EXIT synchronizeFromMain");
        return new OAuthTemplateSyncResult(
                "ok",
                "Synchronized OAuth templates from main.",
                created,
                updated,
                created + updated,
                skipped);
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
                authorizationParameters,
                template.artifactStatus() == null ? ArtifactStatus.SNAPSHOT : template.artifactStatus());
    }

    private void ensureUniqueClientName(String clientName, UUID currentId) {
        OAuthTemplateEntity existing = findEntityByClientName(clientName);
        if (existing == null) {
            return;
        }
        if (currentId != null && currentId.toString().equals(existing.uuid())) {
            return;
        }
        throw new IllegalArgumentException("clientName already exists: " + clientName);
    }

    private OAuthTemplateEntity findEntityByClientName(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            return null;
        }
        long total = templateRepository.count();
        int pageSize = 200;
        int pages = (int) ((total + pageSize - 1) / pageSize);
        for (int page = 0; page < pages; page++) {
            try (var stream = templateRepository.list(page, pageSize)) {
                OAuthTemplateEntity found = stream
                        .filter(e -> e != null && clientName.equals(e.clientName()))
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    private List<String> listMainTemplateFiles(URI root) {
        if ("file".equalsIgnoreCase(root.getScheme())) {
            return listTemplateFilesFromLocalRepository(root);
        }

        GitHubRawRef githubRawRef = parseGitHubRawRef(root);
        if (githubRawRef == null) {
            log.warn("OAuth sync currently supports file:// and raw.githubusercontent.com roots only [root={}]", root);
            return List.of();
        }

        String contentsPath = githubRawRef.basePath().isBlank()
                ? "oauth-templates"
                : githubRawRef.basePath() + "/oauth-templates";
        String apiUrl = "https://api.github.com/repos/"
                + githubRawRef.owner()
                + "/"
                + githubRawRef.repo()
                + "/contents/"
                + contentsPath
                + "?ref="
                + githubRawRef.ref();

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return List.of();
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub listing failed with status " + response.statusCode());
            }

            JsonNode jsonRoot = objectMapper.readTree(response.body());
            if (!jsonRoot.isArray()) {
                return List.of();
            }

            List<String> files = new java.util.ArrayList<>();
            for (JsonNode node : jsonRoot) {
                if (!"file".equals(node.path("type").asText(""))) {
                    continue;
                }
                String name = node.path("name").asText("");
                if (name.endsWith(".json")) {
                    files.add(name);
                }
            }
            return List.copyOf(files);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to list OAuth templates from main: " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list OAuth templates from main: " + ex.getMessage(), ex);
        }
    }

    private OAuthTemplate fetchTemplateFromMain(URI root, String fileName) {
        if ("file".equalsIgnoreCase(root.getScheme())) {
            return fetchTemplateFromLocalRepository(root, fileName);
        }

        String rootValue = root.toString();
        if (rootValue.endsWith("/")) {
            rootValue = rootValue.substring(0, rootValue.length() - 1);
        }
        String url = rootValue + "/oauth-templates/"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub template fetch failed with status " + response.statusCode());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String authorizeEndpoint = json.path("authorizeEndpoint").asText("").trim();
            String tokenEndpoint = json.path("tokenEndpoint").asText("").trim();

            List<String> scopes = new java.util.ArrayList<>();
            JsonNode scopesNode = json.path("scopes");
            if (scopesNode.isArray()) {
                for (JsonNode scope : scopesNode) {
                    String value = scope.asText("").trim();
                    if (!value.isBlank()) {
                        scopes.add(value);
                    }
                }
            }

            Map<String, String> authParams = new LinkedHashMap<>();
            JsonNode authNode = json.path("authorizationParameters");
            if (authNode.isObject()) {
                authNode.properties().forEach(entry -> {
                    String key = entry.getKey() == null ? "" : entry.getKey().trim();
                    String value = entry.getValue() == null ? "" : entry.getValue().asText("").trim();
                    if (!key.isBlank()) {
                        authParams.put(key, value);
                    }
                });
            }

            return new OAuthTemplate(
                    null,
                    json.path("name").asText("").trim(),
                    json.path("clientName").asText("").trim(),
                    json.path("description").asText("").trim(),
                    authorizeEndpoint.isBlank() ? null : URI.create(authorizeEndpoint),
                    tokenEndpoint.isBlank() ? null : URI.create(tokenEndpoint),
                    List.copyOf(scopes),
                    Map.copyOf(authParams),
                    ArtifactStatus.PUBLISHED);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Failed to fetch OAuth template from main [file={}]: {}", fileName, ex.getMessage());
            return null;
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to fetch OAuth template from main [file={}]: {}", fileName, ex.getMessage());
            return null;
        }
    }

    URI resolveSyncRoot() {
        return resolveSyncRoot(null);
    }

    URI resolveSyncRoot(String preferredRepositoryName) {
        if (hubRepositoryRegistryService == null) {
            return DEFAULT_SYNC_ROOT;
        }
        try {
            List<HubRepositoryDefinition> repositories = hubRepositoryRegistryService.resolveRepositories();
            String preferred = preferredRepositoryName == null ? "" : preferredRepositoryName.trim();
            if (!preferred.isBlank()) {
                for (HubRepositoryDefinition repository : repositories) {
                    if (repository != null
                            && repository.name() != null
                            && preferred.equalsIgnoreCase(repository.name())
                            && repository.baseUrl() != null) {
                        return repository.baseUrl();
                    }
                }
            }
            for (HubRepositoryDefinition repository : repositories) {
                if (repository != null
                        && repository.name() != null
                        && "production".equalsIgnoreCase(repository.name())
                        && repository.baseUrl() != null) {
                    return repository.baseUrl();
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to resolve Hub repositories for OAuth sync, using default root: {}", ex.getMessage());
        }
        return DEFAULT_SYNC_ROOT;
    }

    private List<String> listTemplateFilesFromLocalRepository(URI root) {
        try {
            Path templateDir = Path.of(root).resolve("oauth-templates");
            if (!Files.exists(templateDir) || !Files.isDirectory(templateDir)) {
                return List.of();
            }
            try (var stream = Files.list(templateDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".json"))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to list OAuth templates from local repository [{}]: {}", root, ex.getMessage());
            return List.of();
        }
    }

    private OAuthTemplate fetchTemplateFromLocalRepository(URI root, String fileName) {
        try {
            Path file = Path.of(root).resolve("oauth-templates").resolve(fileName).normalize();
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return null;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            return parseTemplateJson(json);
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to fetch OAuth template from local repository [file={}]: {}", fileName, ex.getMessage());
            return null;
        }
    }

    private OAuthTemplate parseTemplateJson(JsonNode json) {
        String authorizeEndpoint = json.path("authorizeEndpoint").asText("").trim();
        String tokenEndpoint = json.path("tokenEndpoint").asText("").trim();

        List<String> scopes = new java.util.ArrayList<>();
        JsonNode scopesNode = json.path("scopes");
        if (scopesNode.isArray()) {
            for (JsonNode scope : scopesNode) {
                String value = scope.asText("").trim();
                if (!value.isBlank()) {
                    scopes.add(value);
                }
            }
        }

        Map<String, String> authParams = new LinkedHashMap<>();
        JsonNode authNode = json.path("authorizationParameters");
        if (authNode.isObject()) {
            authNode.properties().forEach(entry -> {
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                String value = entry.getValue() == null ? "" : entry.getValue().asText("").trim();
                if (!key.isBlank()) {
                    authParams.put(key, value);
                }
            });
        }

        return new OAuthTemplate(
                null,
                json.path("name").asText("").trim(),
                json.path("clientName").asText("").trim(),
                json.path("description").asText("").trim(),
                authorizeEndpoint.isBlank() ? null : URI.create(authorizeEndpoint),
                tokenEndpoint.isBlank() ? null : URI.create(tokenEndpoint),
                List.copyOf(scopes),
                Map.copyOf(authParams),
                ArtifactStatus.PUBLISHED);
    }

    private GitHubRawRef parseGitHubRawRef(URI root) {
        if (root == null || root.getHost() == null) {
            return null;
        }
        if (!"raw.githubusercontent.com".equalsIgnoreCase(root.getHost())) {
            return null;
        }

        String path = root.getPath() == null ? "" : root.getPath();
        String[] segments = path.split("/");
        java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
        for (String segment : segments) {
            String value = segment == null ? "" : segment.trim();
            if (!value.isBlank()) {
                tokens.add(value);
            }
        }
        if (tokens.size() < 3) {
            return null;
        }

        String owner = tokens.get(0);
        String repo = tokens.get(1);
        String ref = tokens.get(2);
        String basePath = "";
        if (tokens.size() > 3) {
            basePath = String.join("/", tokens.subList(3, tokens.size()));
        }
        return new GitHubRawRef(owner, repo, ref, basePath);
    }

    private record GitHubRawRef(String owner, String repo, String ref, String basePath) {
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
                entity.authorizationParameters(),
                entity.artifactStatus());
    }

    public record OAuthTemplateExportPackage(
            String vorkOAuthTemplateExport,
            int version,
            List<OAuthTemplate> templates
    ) {
    }

    public record OAuthTemplateImportResult(
            String status,
            String message,
            int created,
            int updated
    ) {
    }

    public record OAuthTemplateSyncResult(
            String status,
            String message,
            int created,
            int updated,
            int synchronizedCount,
            int skipped
    ) {
    }
}
