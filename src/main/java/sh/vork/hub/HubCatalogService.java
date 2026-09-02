package sh.vork.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import sh.vork.hub.repository.HubRepositoryDefinition;
import sh.vork.hub.repository.HubRepositoryRegistryService;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HubCatalogService {

    private static final Logger log = LoggerFactory.getLogger(HubCatalogService.class);
    private static final long DEFAULT_CACHE_TTL_MS = 86_400_000L;
    private static final String INDEX_PATH = "hub-index.json";

    private final HubRepositoryRegistryService repositoryRegistryService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<String, CachedCatalog> cacheByRepository = new ConcurrentHashMap<>();

    @Autowired
    public HubCatalogService(HubRepositoryRegistryService repositoryRegistryService,
                             ObjectMapper objectMapper) {
        this(repositoryRegistryService, objectMapper, DEFAULT_CACHE_TTL_MS);
    }

    HubCatalogService(HubRepositoryRegistryService repositoryRegistryService,
                      ObjectMapper objectMapper,
                      long cacheTtlMs) {
        this.repositoryRegistryService = repositoryRegistryService;
        this.objectMapper = objectMapper;
        this.cacheTtlMs = cacheTtlMs <= 0 ? DEFAULT_CACHE_TTL_MS : cacheTtlMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<HubCatalogItem> listCatalogItems(String repositoryName, String typeFilter, String query) {
        log.debug("ENTER listCatalogItems: repositoryName={}, typeFilter={}, query={}", repositoryName, typeFilter, query);
        HubRepositoryDefinition repo = resolveRepository(repositoryName);
        List<HubCatalogItem> sourceItems = loadCatalogWithCache(repo, false);

        String normalizedTypeFilter = normalizeBlank(typeFilter).map(this::normalizeType).orElse("");
        String normalizedQuery = normalizeBlank(query).map(v -> v.toLowerCase(Locale.ROOT)).orElse("");

        List<HubCatalogItem> items = new ArrayList<>();
        for (HubCatalogItem item : sourceItems) {
            String normalizedType = normalizeType(item.type());
            if (normalizedType.isBlank() || item.name() == null || item.name().isBlank()) {
                continue;
            }
            if (!normalizedTypeFilter.isBlank() && !normalizedType.equals(normalizedTypeFilter)) {
                continue;
            }

            String haystack = (item.name() + " " + normalizedType + " "
                    + safe(item.artifactPath()) + " "
                    + safe(item.description())).toLowerCase(Locale.ROOT);
            if (!normalizedQuery.isBlank() && !haystack.contains(normalizedQuery)) {
                continue;
            }
            items.add(item);
        }

        log.debug("EXIT listCatalogItems: repository={}, count={}", repo.name(), items.size());
        return List.copyOf(items);
    }

    @Scheduled(fixedDelayString = "${vork.hub.scan.refresh-ms:86400000}")
    public void refreshCatalogCache() {
        log.debug("ENTER refreshCatalogCache");
        try {
            List<HubRepositoryDefinition> repositories = repositoryRegistryService.resolveRepositories();
            for (HubRepositoryDefinition repository : repositories) {
                if (repository == null || !repository.available()) {
                    continue;
                }
                try {
                    loadCatalogWithCache(repository, true);
                } catch (RuntimeException | LinkageError ex) {
                    log.warn("Catalog refresh failed for repository {}: {}", repository.name(), ex.getMessage());
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            log.warn("refreshCatalogCache failed: {}", ex.getMessage());
        }
        log.debug("EXIT refreshCatalogCache: cacheSize={}", cacheByRepository.size());
    }

    public ArtifactPayload loadArtifact(String repositoryName, String relativePath) {
        HubRepositoryDefinition repo = resolveRepository(repositoryName);
        String safePath = sanitizeRelativePath(relativePath);
        byte[] bytes = readRepositoryPath(repo, safePath);
        String mediaType = mediaTypeForPath(safePath);
        return new ArtifactPayload(safePath, mediaType, bytes);
    }

    public InstallPackage prepareInstallPackage(String repositoryName, String type, String installPath) {
        log.debug("ENTER prepareInstallPackage: repositoryName={}, type={}, installPath={}", repositoryName, type, installPath);
        String normalizedType = normalizeType(type);
        String endpoint = endpointForType(normalizedType);
        if (endpoint == null) {
            throw new IllegalArgumentException("Unsupported install type: " + type);
        }

        ArtifactPayload artifact = loadArtifact(repositoryName, installPath);
        String payloadBase64 = Base64.getEncoder().encodeToString(artifact.bytes());
        String mode = normalizedType.equals("surface") ? "multipart-zip" : "json";

        InstallPackage pkg = new InstallPackage(
                normalizedType,
                artifact.path(),
                endpoint,
                mode,
                artifact.mediaType(),
                fileNameFromPath(artifact.path()),
                payloadBase64
        );
        log.debug("EXIT prepareInstallPackage: endpoint={}, mode={}, bytes={}", endpoint, mode, artifact.bytes().length);
        return pkg;
    }

    private HubRepositoryDefinition resolveRepository(String repositoryName) {
        List<HubRepositoryDefinition> repositories = repositoryRegistryService.resolveRepositories();
        String selected = normalizeBlank(repositoryName).orElse("Production");

        HubRepositoryDefinition match = repositories.stream()
                .filter(Objects::nonNull)
                .filter(repo -> repo.name() != null)
                .filter(repo -> repo.name().equalsIgnoreCase(selected))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown repository: " + selected));

        if (!match.available()) {
            throw new IllegalStateException("Repository is unavailable: " + match.name());
        }
        return match;
    }

    private List<HubCatalogItem> loadCatalogWithCache(HubRepositoryDefinition repository, boolean forceRefresh) {
        String cacheKey = cacheKey(repository);
        long now = System.currentTimeMillis();
        CachedCatalog cached = cacheByRepository.get(cacheKey);
        if (!forceRefresh && cached != null && (now - cached.createdAtEpochMs()) < cacheTtlMs) {
            return cached.items();
        }

        synchronized (cacheByRepository) {
            cached = cacheByRepository.get(cacheKey);
            if (!forceRefresh && cached != null && (now - cached.createdAtEpochMs()) < cacheTtlMs) {
                return cached.items();
            }
            List<HubCatalogItem> rebuilt = buildCatalog(repository);
            cacheByRepository.put(cacheKey, new CachedCatalog(now, List.copyOf(rebuilt)));
            return rebuilt;
        }
    }

    private List<HubCatalogItem> buildCatalog(HubRepositoryDefinition repository) {
        try {
            return buildCatalogFromIndex(repository);
        } catch (RuntimeException ex) {
            log.info("Hub index not available for repository {}. Falling back to scan. Reason: {}",
                    repository.name(), ex.getMessage());
            return scanRepository(repository);
        }
    }

    private List<HubCatalogItem> buildCatalogFromIndex(HubRepositoryDefinition repository) {
        byte[] json = readRepositoryPath(repository, INDEX_PATH);
        JsonNode root = parseJson(json, INDEX_PATH);
        JsonNode components = root.path("components");
        if (!components.isArray()) {
            throw new IllegalStateException("hub-index.json is missing an array field named 'components'.");
        }

        List<HubCatalogItem> items = new ArrayList<>();
        int index = 0;
        for (JsonNode node : components) {
            index++;
            String rawType = node.path("type").asText("");
            String normalizedType = normalizeType(rawType);
            String name = node.path("name").asText("").trim();
            String artifactPath = node.path("artifactPath").asText("").trim();
            String installPath = node.path("installPath").asText("").trim();
            String docPath = node.path("docPath").asText("").trim();
            String logoPath = node.path("logoPath").asText("").trim();
            String description = node.path("description").asText("").trim();
            if (description.isBlank()) {
                description = "Installable " + normalizedType.replace('-', ' ') + " artifact.";
            }

            String targetEndpoint = endpointForType(normalizedType);
            if (targetEndpoint == null || normalizedType.isBlank() || name.isBlank() || installPath.isBlank()) {
                continue;
            }

            items.add(new HubCatalogItem(
                    buildItemId(normalizedType, installPath, index),
                    normalizedType,
                    name,
                    description,
                    artifactPath,
                    installPath,
                    docPath,
                    logoPath,
                    targetEndpoint,
                    repository.name(),
                    repository.baseUrl() == null ? "" : repository.baseUrl().toString()
            ));
        }
        return List.copyOf(items);
    }

    private List<HubCatalogItem> scanRepository(HubRepositoryDefinition repository) {
        URI base = repository.baseUrl();
        if (base == null) {
            throw new IllegalStateException("Repository base URL is not configured for " + repository.name());
        }

        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        List<ScannedInstallFile> files;
        if ("file".equals(scheme)) {
            files = scanFileRepository(base);
        } else if ("https".equals(scheme) && "raw.githubusercontent.com".equalsIgnoreCase(base.getHost())) {
            files = scanRawGithubRepository(base);
        } else {
            throw new IllegalStateException("Repository scan unsupported for base URL: " + base);
        }

        files.sort(Comparator.comparing(ScannedInstallFile::type).thenComparing(ScannedInstallFile::relativePath));

        List<HubCatalogItem> items = new ArrayList<>();
        int index = 0;
        for (ScannedInstallFile file : files) {
            index++;
            InstallMetadata metadata = inspectInstallMetadata(repository, file);
            String endpoint = endpointForType(file.type());
            if (endpoint == null) {
                continue;
            }

            String logoPath = resolveLogoPath(repository, file, metadata);
            String docPath = resolveDocPath(repository, file, metadata);
            items.add(new HubCatalogItem(
                    buildItemId(file.type(), file.relativePath(), index),
                    file.type(),
                    metadata.name(),
                    metadata.description(),
                    file.relativePath(),
                    file.relativePath(),
                    docPath,
                    logoPath,
                    endpoint,
                    repository.name(),
                    repository.baseUrl() == null ? "" : repository.baseUrl().toString()
            ));
        }
        return List.copyOf(items);
    }

    private List<ScannedInstallFile> scanFileRepository(URI baseUri) {
        Path root = Path.of(baseUri);
        Path installRoot = root.resolve("install");
        List<ScannedInstallFile> files = new ArrayList<>();

        addInstallFilesForType(root, installRoot.resolve("agents"), "agent", files);
        addInstallFilesForType(root, installRoot.resolve("jobs"), "job", files);
        addInstallFilesForType(root, installRoot.resolve("surfaces"), "surface", files);
        addInstallFilesForType(root, installRoot.resolve("skills"), "skill-group", files);
        addInstallFilesForType(root, installRoot.resolve("reflections"), "reflection-group", files);
        addInstallFilesForType(root, installRoot.resolve("oauth-templates"), "oauth-template", files);

        return files;
    }

    private void addInstallFilesForType(Path repositoryRoot,
                                        Path typeDirectory,
                                        String type,
                                        List<ScannedInstallFile> files) {
        if (!Files.exists(typeDirectory) || !Files.isDirectory(typeDirectory)) {
            return;
        }

        try (var stream = Files.list(typeDirectory)) {
            stream.filter(Files::isRegularFile)
                    .forEach(path -> {
                        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!filename.endsWith(".json") && !filename.endsWith(".zip")) {
                            return;
                        }
                        String relative = repositoryRoot.relativize(path).toString().replace('\\', '/');
                        files.add(new ScannedInstallFile(type, relative));
                    });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed scanning install directory: " + typeDirectory + " - " + ex.getMessage(), ex);
        }
    }

    private List<ScannedInstallFile> scanRawGithubRepository(URI baseUri) {
        GitHubRawRef ref = parseGitHubRawRef(baseUri);
        if (ref == null) {
            throw new IllegalStateException("Unsupported raw GitHub URL: " + baseUri);
        }

        List<ScannedInstallFile> files = new ArrayList<>();
        addGitHubInstallFiles(ref, "agents", "agent", files);
        addGitHubInstallFiles(ref, "jobs", "job", files);
        addGitHubInstallFiles(ref, "surfaces", "surface", files);
        addGitHubInstallFiles(ref, "skills", "skill-group", files);
        addGitHubInstallFiles(ref, "reflections", "reflection-group", files);
        addGitHubInstallFiles(ref, "oauth-templates", "oauth-template", files);
        return files;
    }

    private void addGitHubInstallFiles(GitHubRawRef ref,
                                       String installFolder,
                                       String type,
                                       List<ScannedInstallFile> files) {
        String repoPath = ref.basePath().isBlank()
                ? "install/" + installFolder
                : ref.basePath() + "/install/" + installFolder;

        String url = "https://api.github.com/repos/"
                + encodePathSegment(ref.owner())
                + "/"
                + encodePathSegment(ref.repo())
                + "/contents/"
                + encodePath(repoPath)
                + "?ref="
                + encodePathSegment(ref.ref());

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "vork-hub-scanner")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("GitHub API request failed for " + repoPath + " (HTTP " + response.statusCode() + ")");
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray()) {
                return;
            }

            for (JsonNode node : root) {
                if (!"file".equalsIgnoreCase(node.path("type").asText(""))) {
                    continue;
                }
                String fullPath = node.path("path").asText("").trim();
                String relative = stripBasePath(fullPath, ref.basePath());
                String lower = relative.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".json") && !lower.endsWith(".zip")) {
                    continue;
                }
                files.add(new ScannedInstallFile(type, relative));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed parsing GitHub contents for " + repoPath + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while scanning GitHub path " + repoPath, ex);
        }
    }

    private InstallMetadata inspectInstallMetadata(HubRepositoryDefinition repository, ScannedInstallFile file) {
        String fallbackName = humanizeFilename(file.relativePath());
        String fallbackDescription = "Installable " + file.type().replace('-', ' ') + " artifact.";

        if (!file.relativePath().toLowerCase(Locale.ROOT).endsWith(".json")) {
            return new InstallMetadata(fallbackName, fallbackDescription, "", "", "", "");
        }

        try {
            byte[] bytes = readRepositoryPath(repository, file.relativePath());
            JsonNode root = objectMapper.readTree(bytes);

            String name = extractName(file.type(), root);
            String description = extractDescription(file.type(), root);
            String groupId = extractGroupId(file.type(), root);
            String artifactId = extractArtifactId(file.type(), root);
            String version = extractVersion(file.type(), root);
            String clientName = extractClientName(file.type(), root);
            if (name.isBlank()) {
                name = fallbackName;
            }
            if (description.isBlank()) {
                description = fallbackDescription;
            }
            return new InstallMetadata(name, description, groupId, artifactId, version, clientName);
        } catch (RuntimeException | IOException ex) {
            log.debug("Failed to inspect metadata for {}: {}", file.relativePath(), ex.getMessage());
            return new InstallMetadata(fallbackName, fallbackDescription, "", "", "", "");
        }
    }

    private String resolveLogoPath(HubRepositoryDefinition repository,
                                   ScannedInstallFile file,
                                   InstallMetadata metadata) {
        List<String> candidates = new ArrayList<>();
        String baseName = installBaseName(file.relativePath());

        if ("oauth-template".equals(file.type()) && !metadata.clientName().isBlank()) {
            candidates.add("oauth-templates/" + metadata.clientName() + ".svg");
            candidates.add("oauth-templates/" + metadata.clientName() + ".png");
            candidates.add("oauth-templates/" + metadata.clientName() + ".jpg");
        }

        String rootFolder = artifactRootFolder(file.type());
        if (!rootFolder.isBlank() && !metadata.groupId().isBlank() && !metadata.artifactId().isBlank()) {
            String version = metadata.version().isBlank() ? "1.0" : metadata.version();
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/" + version + "/logo.svg");
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/" + version + "/logo.png");
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/" + version + "/logo.jpg");
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/logo.svg");
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/logo.png");
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/logo.jpg");
        }

        candidates.add("logos/" + baseName + ".svg");
        candidates.add("logos/" + baseName + ".png");
        candidates.add("logos/" + baseName + ".jpg");

        return findFirstExistingPath(repository, candidates);
    }

    private String resolveDocPath(HubRepositoryDefinition repository,
                                  ScannedInstallFile file,
                                  InstallMetadata metadata) {
        List<String> candidates = new ArrayList<>();
        String baseName = installBaseName(file.relativePath());

        if ("oauth-template".equals(file.type()) && !metadata.clientName().isBlank()) {
            candidates.add("oauth-templates/" + metadata.clientName() + ".md");
        }

        String rootFolder = artifactRootFolder(file.type());
        if (!rootFolder.isBlank() && !metadata.groupId().isBlank() && !metadata.artifactId().isBlank()) {
            String version = metadata.version().isBlank() ? "1.0" : metadata.version();
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/" + version + "/README.md");
            candidates.add(rootFolder + "/" + metadata.groupId() + "/" + metadata.artifactId() + "/README.md");
        }

        candidates.add("docs/" + baseName + ".md");
        return findFirstExistingPath(repository, candidates);
    }

    private String findFirstExistingPath(HubRepositoryDefinition repository, List<String> candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                readRepositoryPath(repository, candidate);
                return candidate;
            } catch (RuntimeException ignored) {
                // Candidate does not exist or is not accessible; continue probing.
            }
        }
        return "";
    }

    private String extractGroupId(String type, JsonNode root) {
        return switch (type) {
            case "agent" -> root.path("agent").path("groupId").asText("").trim();
            case "job" -> root.path("job").path("groupId").asText("").trim();
            case "skill-group", "reflection-group" -> root.path("group").path("groupId").asText("").trim();
            default -> "";
        };
    }

    private String extractArtifactId(String type, JsonNode root) {
        return switch (type) {
            case "agent" -> root.path("agent").path("artifactId").asText("").trim();
            case "job" -> root.path("job").path("artifactId").asText("").trim();
            case "skill-group", "reflection-group" -> root.path("group").path("artifactId").asText("").trim();
            default -> "";
        };
    }

    private String extractVersion(String type, JsonNode root) {
        return switch (type) {
            case "agent" -> root.path("agent").path("version").asText("").trim();
            case "job" -> root.path("job").path("version").asText("").trim();
            case "skill-group", "reflection-group" -> root.path("group").path("version").asText("").trim();
            default -> "";
        };
    }

    private String extractClientName(String type, JsonNode root) {
        if (!"oauth-template".equals(type)) {
            return "";
        }
        if (!root.path("templates").isArray() || root.path("templates").isEmpty()) {
            return "";
        }
        return root.path("templates").get(0).path("clientName").asText("").trim();
    }

    private String artifactRootFolder(String type) {
        return switch (type) {
            case "agent" -> "agents";
            case "job" -> "jobs";
            case "surface" -> "surfaces";
            case "skill-group" -> "skills";
            case "reflection-group" -> "reflections";
            default -> "";
        };
    }

    private static String installBaseName(String installPath) {
        String fileName = fileNameFromPath(installPath);
        return fileName.replaceAll("\\.[^.]+$", "");
    }

    private String extractName(String type, JsonNode root) {
        return switch (type) {
            case "agent" -> root.path("agent").path("name").asText("").trim();
            case "job" -> root.path("job").path("name").asText("").trim();
            case "skill-group" -> root.path("group").path("name").asText("").trim();
            case "reflection-group" -> root.path("group").path("name").asText("").trim();
            case "oauth-template" -> root.path("templates").isArray() && !root.path("templates").isEmpty()
                    ? root.path("templates").get(0).path("name").asText("").trim()
                    : "";
            default -> "";
        };
    }

    private String extractDescription(String type, JsonNode root) {
        return switch (type) {
            case "agent" -> root.path("agent").path("systemPrompt").asText("").trim();
            case "job" -> root.path("job").path("aiPrompt").asText("").trim();
            case "skill-group" -> root.path("group").path("category").asText("").trim();
            case "reflection-group" -> root.path("group").path("description").asText("").trim();
            case "oauth-template" -> root.path("templates").isArray() && !root.path("templates").isEmpty()
                    ? root.path("templates").get(0).path("description").asText("").trim()
                    : "";
            default -> "";
        };
    }

    private String stripBasePath(String fullPath, String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return fullPath;
        }
        String normalizedBase = basePath.endsWith("/") ? basePath : basePath + "/";
        if (fullPath.startsWith(normalizedBase)) {
            return fullPath.substring(normalizedBase.length());
        }
        return fullPath;
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
        ArrayList<String> tokens = new ArrayList<>();
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
        String basePath = tokens.size() > 3 ? String.join("/", tokens.subList(3, tokens.size())) : "";
        return new GitHubRawRef(owner, repo, ref, basePath);
    }

    private static String humanizeFilename(String path) {
        String filename = fileNameFromPath(path);
        String base = filename.replaceAll("\\.[^.]+$", "");
        String normalized = base.replace('-', ' ').replace('_', ' ').trim();
        if (normalized.isBlank()) {
            normalized = "Artifact";
        }
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String cacheKey(HubRepositoryDefinition repository) {
        return (repository.name() == null ? "" : repository.name().toLowerCase(Locale.ROOT))
                + "|"
                + (repository.baseUrl() == null ? "" : repository.baseUrl().toString());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private byte[] readRepositoryPath(HubRepositoryDefinition repository, String relativePath) {
        String safePath = sanitizeRelativePath(relativePath);
        URI base = repository.baseUrl();
        if (base == null) {
            throw new IllegalStateException("Repository base URL is not configured for " + repository.name());
        }

        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        try {
            if ("file".equals(scheme)) {
                Path root = Path.of(base);
                Path target = root.resolve(safePath).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("Path escapes repository root: " + safePath);
                }
                if (!Files.exists(target) || Files.isDirectory(target)) {
                    throw new IllegalArgumentException("Artifact not found: " + safePath);
                }
                return Files.readAllBytes(target);
            }

            if ("https".equals(scheme)) {
                URI fullUri = resolveHttpPath(base, safePath);
                HttpRequest request = HttpRequest.newBuilder(fullUri)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Repository request failed for " + safePath + " (HTTP " + status + ")");
                }
                return response.body();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed reading repository artifact '" + safePath + "': " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading repository artifact '" + safePath + "'", ex);
        }

        throw new IllegalArgumentException("Unsupported repository URL scheme: " + scheme);
    }

    private JsonNode parseJson(byte[] bytes, String sourceName) {
        try {
            return objectMapper.readTree(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to parse JSON from " + sourceName + ": " + ex.getMessage(), ex);
        }
    }

    private static URI resolveHttpPath(URI base, String relativePath) {
        String root = base.toString();
        if (!root.endsWith("/")) {
            root = root + "/";
        }
        String encoded = encodePath(relativePath);
        return URI.create(root + encoded);
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

    private static String sanitizeRelativePath(String relativePath) {
        String value = relativePath == null ? "" : relativePath.trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Artifact path is required.");
        }
        value = value.replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("../") || value.contains("/../") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid artifact path: " + relativePath);
        }
        return value;
    }

    private static Optional<String> normalizeBlank(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
    }

    private String normalizeType(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "agent", "agents" -> "agent";
            case "job", "jobs" -> "job";
            case "surface", "surfaces" -> "surface";
            case "skill-group", "skillgroup", "skills" -> "skill-group";
            case "reflection-group", "reflectiongroup", "reflections" -> "reflection-group";
            case "oauth-template", "oauthtemplate", "oauth-templates" -> "oauth-template";
            default -> normalized;
        };
    }

    private String endpointForType(String normalizedType) {
        return switch (normalizedType) {
            case "agent" -> "/api/agents/import";
            case "job" -> "/api/jobs/import";
            case "surface" -> "/api/surfaces/import";
            case "skill-group" -> "/api/skill-groups/import";
            case "reflection-group" -> "/api/reflection-groups/import";
            case "oauth-template" -> "/api/oauth-templates/import";
            default -> null;
        };
    }

    private static String buildItemId(String normalizedType, String installPath, int index) {
        String base = (normalizedType + "-" + (installPath == null ? "" : installPath)).toLowerCase(Locale.ROOT);
        String compact = base.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (compact.isBlank()) {
            compact = "item-" + index;
        }
        return compact + "-" + index;
    }

    private static String fileNameFromPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String mediaTypeForPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private record CachedCatalog(long createdAtEpochMs, List<HubCatalogItem> items) {
    }

    private record ScannedInstallFile(String type, String relativePath) {
    }

        private record InstallMetadata(
            String name,
            String description,
            String groupId,
            String artifactId,
            String version,
            String clientName
        ) {
    }

    private record GitHubRawRef(String owner, String repo, String ref, String basePath) {
    }

    public record HubCatalogItem(
            String id,
            String type,
            String name,
            String description,
            String artifactPath,
            String installPath,
            String docPath,
            String logoPath,
            String installEndpoint,
            String repositoryName,
            String repositoryBaseUrl
    ) {
    }

    public record ArtifactPayload(String path, String mediaType, byte[] bytes) {
    }

    public record InstallPackage(
            String type,
            String installPath,
            String installEndpoint,
            String installMode,
            String mediaType,
            String fileName,
            String payloadBase64
    ) {
    }
}
