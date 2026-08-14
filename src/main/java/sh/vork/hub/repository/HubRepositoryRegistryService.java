package sh.vork.hub.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves Hub repositories from defaults plus optional runtime property entries.
 */
@Service
public class HubRepositoryRegistryService {

    private static final Logger log = LoggerFactory.getLogger(HubRepositoryRegistryService.class);

    static final String ADDITIONAL_REPOSITORIES_KEY = "vork.additionalRepositories";
    static final String FAIL_FAST_KEY = "vork.additionalRepositoriesFailFast";

    static final URI PRODUCTION_ROOT = URI.create("https://raw.githubusercontent.com/justvork/vork-central/main");
    static final URI STAGING_ROOT = URI.create("https://raw.githubusercontent.com/justvork/vork-central/staging");

    private final Environment environment;
    private final HttpClient httpClient;

    @Autowired
    public HubRepositoryRegistryService(Environment environment) {
        this(
                environment,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    HubRepositoryRegistryService(Environment environment, HttpClient httpClient) {
        this.environment = environment;
        this.httpClient = httpClient;
    }

    public List<HubRepositoryDefinition> resolveRepositories() {
        log.debug("ENTER resolveRepositories");

        boolean failFast = environment.getProperty(FAIL_FAST_KEY, Boolean.class, false);
        String raw = environment.getProperty(ADDITIONAL_REPOSITORIES_KEY, "");

        List<HubRepositoryDefinition> repositories = new ArrayList<>();
        repositories.add(new HubRepositoryDefinition("Production", PRODUCTION_ROOT, false, true, "Built-in repository"));
        repositories.add(new HubRepositoryDefinition("Staging", STAGING_ROOT, false, true, "Built-in repository"));

        Set<String> seenNames = new HashSet<>();
        seenNames.add("production");
        seenNames.add("staging");

        if (raw == null || raw.isBlank()) {
            log.debug("EXIT resolveRepositories: count={} (no additional repositories configured)", repositories.size());
            return List.copyOf(repositories);
        }

        String[] entries = raw.split(",");
        int accepted = 0;
        for (String entry : entries) {
            String token = entry == null ? "" : entry.trim();
            if (token.isBlank()) {
                continue;
            }

            HubRepositoryDefinition parsed = parseEntry(token, seenNames, failFast);
            if (parsed != null) {
                repositories.add(parsed);
                accepted++;
            }
        }

        log.debug("EXIT resolveRepositories: count={}, additionalAccepted={}", repositories.size(), accepted);
        return List.copyOf(repositories);
    }

    private HubRepositoryDefinition parseEntry(String token, Set<String> seenNames, boolean failFast) {
        int eq = token.indexOf('=');
        if (eq <= 0 || eq == token.length() - 1) {
            return invalid(token, "Expected format name=URL", failFast);
        }

        String name = token.substring(0, eq).trim();
        String url = token.substring(eq + 1).trim();

        if (name.isBlank()) {
            return invalid(token, "Repository name must be non-blank", failFast);
        }
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (!seenNames.add(normalizedName)) {
            return invalid(token, "Duplicate repository name: " + name, failFast);
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return invalid(token, "Repository URL is invalid: " + ex.getMessage(), failFast);
        }

        if (!uri.isAbsolute()) {
            return invalid(token, "Repository URL must be absolute", failFast);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("file") && !scheme.equals("https")) {
            return invalid(token, "Unsupported URL scheme: " + scheme, failFast);
        }

        if (scheme.equals("file")) {
            Path path;
            try {
                path = Path.of(uri);
            } catch (Exception ex) {
                return invalid(token, "Invalid file URL path: " + ex.getMessage(), failFast);
            }
            if (!Files.exists(path) || !Files.isDirectory(path)) {
                return invalid(token, "File URL must reference an existing directory", failFast);
            }
            return new HubRepositoryDefinition(name, uri, true, true, "Configured local repository");
        }

        boolean available = probeHttpBase(uri);
        if (!available && failFast) {
            throw new IllegalStateException("Repository unavailable: " + token);
        }

        return new HubRepositoryDefinition(
                name,
                uri,
                true,
                available,
                available ? "Configured remote repository" : "Repository unreachable during startup probe");
    }

    private HubRepositoryDefinition invalid(String token, String reason, boolean failFast) {
        if (failFast) {
            throw new IllegalStateException("Invalid repository entry '" + token + "': " + reason);
        }
        log.warn("Skipping additional repository '{}' : {}", token, reason);
        return null;
    }

    private boolean probeHttpBase(URI baseUri) {
        HttpRequest headRequest = HttpRequest.newBuilder(baseUri)
                .timeout(Duration.ofSeconds(6))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<Void> headResponse = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());
            int status = headResponse.statusCode();
            if (status == 405) {
                HttpRequest getRequest = HttpRequest.newBuilder(baseUri)
                        .timeout(Duration.ofSeconds(6))
                        .GET()
                        .build();
                HttpResponse<Void> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.discarding());
                return isReachableStatus(getResponse.statusCode());
            }
            return isReachableStatus(status);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Repository probe interrupted for {}", baseUri);
            return false;
        } catch (IOException ex) {
            log.warn("Repository probe failed for {}: {}", baseUri, ex.getMessage());
            return false;
        }
    }

    private static boolean isReachableStatus(int status) {
        return (status >= 200 && status < 400) || status == 401 || status == 403;
    }
}
