package sh.vork.github.contribution;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class DefaultGitHubContributionApiClient implements GitHubContributionApiClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultGitHubContributionApiClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DefaultGitHubContributionApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public String getAuthenticatedLogin(String accessToken) {
        log.debug("ENTER getAuthenticatedLogin");
        JsonNode json = sendJson(
                baseRequest(accessToken, "https://api.github.com/user")
                        .GET()
                        .build());
        String login = json.path("login").asText("");
        if (login.isBlank()) {
            throw new IllegalStateException("GitHub did not return authenticated user login");
        }
        log.debug("EXIT getAuthenticatedLogin: login={}", login);
        return login;
    }

    @Override
    public ForkRef ensureFork(String accessToken, String upstreamOwner, String upstreamRepository) {
        log.debug("ENTER ensureFork: upstream={}/{}", upstreamOwner, upstreamRepository);
        String url = "https://api.github.com/repos/" + encode(upstreamOwner) + "/" + encode(upstreamRepository) + "/forks";

        JsonNode json = sendJson(
                baseRequest(accessToken, url)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build());

        String owner = json.path("owner").path("login").asText("");
        String repository = json.path("name").asText("");
        if (owner.isBlank() || repository.isBlank()) {
            throw new IllegalStateException("GitHub did not return fork owner/repository");
        }
        log.debug("EXIT ensureFork: fork={}/{}", owner, repository);
        return new ForkRef(owner, repository);
    }

    @Override
    public String getDefaultBranch(String accessToken, String owner, String repository) {
        log.debug("ENTER getDefaultBranch: repo={}/{}", owner, repository);
        String url = "https://api.github.com/repos/" + encode(owner) + "/" + encode(repository);
        JsonNode json = sendJson(
                baseRequest(accessToken, url)
                        .GET()
                        .build());
        String branch = json.path("default_branch").asText("");
        if (branch.isBlank()) {
            throw new IllegalStateException("GitHub did not return default_branch for " + owner + "/" + repository);
        }
        log.debug("EXIT getDefaultBranch: branch={}", branch);
        return branch;
    }

    @Override
    public String getBranchHeadSha(String accessToken, String owner, String repository, String branch) {
        log.debug("ENTER getBranchHeadSha: repo={}/{}, branch={}", owner, repository, branch);
        String url = "https://api.github.com/repos/" + encode(owner) + "/" + encode(repository)
                + "/git/ref/heads/" + encode(branch);
        JsonNode json = sendJson(
                baseRequest(accessToken, url)
                        .GET()
                        .build());
        String sha = json.path("object").path("sha").asText("");
        if (sha.isBlank()) {
            throw new IllegalStateException("GitHub did not return branch head sha for " + branch);
        }
        log.debug("EXIT getBranchHeadSha: sha={}", sha);
        return sha;
    }

    @Override
    public void createBranch(String accessToken,
                             String owner,
                             String repository,
                             String branch,
                             String fromSha) {
        log.debug("ENTER createBranch: repo={}/{}, branch={}", owner, repository, branch);
        String url = "https://api.github.com/repos/" + encode(owner) + "/" + encode(repository) + "/git/refs";
        Map<String, Object> body = Map.of("ref", "refs/heads/" + branch, "sha", fromSha);
        sendJson(
                baseRequest(accessToken, url)
                        .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                        .build());
        log.debug("EXIT createBranch: branch={}", branch);
    }

    @Override
    public void createOrUpdateFile(String accessToken,
                                   String owner,
                                   String repository,
                                   String branch,
                                   String path,
                                   String base64Content,
                                   String commitMessage) {
        log.debug("ENTER createOrUpdateFile: repo={}/{}, branch={}, path={}", owner, repository, branch, path);
        String url = "https://api.github.com/repos/" + encode(owner) + "/" + encode(repository)
                + "/contents/" + encodePath(path);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", commitMessage);
        body.put("content", base64Content);
        body.put("branch", branch);
        sendJson(
                baseRequest(accessToken, url)
                        .PUT(HttpRequest.BodyPublishers.ofString(toJson(body)))
                        .build());
        log.debug("EXIT createOrUpdateFile: path={}", path);
    }

    @Override
    public PullRequestRef createPullRequest(String accessToken,
                                            String upstreamOwner,
                                            String upstreamRepository,
                                            String title,
                                            String body,
                                            String headOwner,
                                            String headBranch,
                                            String baseBranch) {
        log.debug("ENTER createPullRequest: upstream={}/{}, baseBranch={}, head={}:{}",
                upstreamOwner, upstreamRepository, baseBranch, headOwner, headBranch);
        String url = "https://api.github.com/repos/" + encode(upstreamOwner) + "/" + encode(upstreamRepository) + "/pulls";

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put("body", body);
        request.put("head", headOwner + ":" + headBranch);
        request.put("base", baseBranch);

        JsonNode json = sendJson(
                baseRequest(accessToken, url)
                        .POST(HttpRequest.BodyPublishers.ofString(toJson(request)))
                        .build());
        long number = json.path("number").asLong(0L);
        String htmlUrl = json.path("html_url").asText("");
        if (number <= 0 || htmlUrl.isBlank()) {
            throw new IllegalStateException("GitHub did not return pull request details");
        }
        log.debug("EXIT createPullRequest: number={}, url={}", number, htmlUrl);
        return new PullRequestRef(number, htmlUrl);
    }

    @Override
    public PullRequestStatus getPullRequestStatus(String owner,
                                                  String repository,
                                                  long pullRequestNumber) {
        log.debug("ENTER getPullRequestStatus: repo={}/{}, prNumber={}", owner, repository, pullRequestNumber);
        String url = "https://api.github.com/repos/" + encode(owner) + "/" + encode(repository)
                + "/pulls/" + pullRequestNumber;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub PR status check failed with status " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = objectMapper.readTree(response.body());
            boolean merged = !json.path("merged_at").isNull();
            String state = json.path("state").asText("");

            PullRequestStatus status;
            if (merged) {
                status = PullRequestStatus.MERGED;
            } else if ("open".equalsIgnoreCase(state)) {
                status = PullRequestStatus.OPEN;
            } else {
                status = PullRequestStatus.CLOSED_UNMERGED;
            }

            log.debug("EXIT getPullRequestStatus: status={}", status);
            return status;
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub PR status check failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean pathExistsInBranch(String owner,
                                      String repository,
                                      String branch,
                                      String path) {
        log.debug("ENTER pathExistsInBranch: repo={}/{}, branch={}, path={}", owner, repository, branch, path);
        String url = "https://api.github.com/repos/" + encode(owner) + "/" + encode(repository)
                + "/contents/" + encodePath(path) + "?ref=" + encode(branch);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                log.debug("EXIT pathExistsInBranch: exists=false");
                return false;
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub branch path check failed with status " + response.statusCode() + ": " + response.body());
            }
            log.debug("EXIT pathExistsInBranch: exists=true");
            return true;
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub branch path check failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<String> listDirectoryNamesInBranch(String owner,
                                                   String repository,
                                                   String branch,
                                                   String path) {
        log.debug("ENTER listDirectoryNamesInBranch: repo={}/{}, branch={}, path={}", owner, repository, branch, path);
        String url = "https://api.github.com/repos/" + encode(owner) + "/" + encode(repository)
                + "/contents/" + encodePath(path) + "?ref=" + encode(branch);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                log.debug("EXIT listDirectoryNamesInBranch: count=0 (path missing)");
                return List.of();
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub directory listing failed with status " + response.statusCode() + ": " + response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            if (!json.isArray()) {
                log.debug("EXIT listDirectoryNamesInBranch: count=0 (non-array response)");
                return List.of();
            }

            List<String> names = new ArrayList<>();
            for (JsonNode entry : json) {
                if (!"dir".equals(entry.path("type").asText(""))) {
                    continue;
                }
                String name = entry.path("name").asText("");
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
            log.debug("EXIT listDirectoryNamesInBranch: count={}", names.size());
            return List.copyOf(names);
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub directory listing failed: " + ex.getMessage(), ex);
        }
    }

    private HttpRequest.Builder baseRequest(String accessToken, String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json");
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub API call failed with status " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub API call failed: " + ex.getMessage(), ex);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize GitHub request payload", ex);
        }
    }

    private static String encodePath(String value) {
        String[] parts = value.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(encode(parts[i]));
        }
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
