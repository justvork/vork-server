package sh.vork.github.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Service
public class GitHubDeviceFlowService {

    private static final Logger log = LoggerFactory.getLogger(GitHubDeviceFlowService.class);

    private final DatabaseRepository<GitHubDeviceFlowSession> sessionRepository;
    private final GitHubDeviceFlowHttpClient httpClient;
    private final GitHubDeviceFlowAuthProvider authProvider;
    private final String clientId;
    private final String defaultScope;

    @Autowired
    public GitHubDeviceFlowService(RepositoryFactory factory,
                                   GitHubDeviceFlowHttpClient httpClient,
                                   GitHubDeviceFlowAuthProvider authProvider,
                                   @Value("${vork.github.oauth.device.client-id:${GITHUB_OAUTH_CLIENT_ID:}}") String clientId,
                                   @Value("${vork.github.oauth.device.scope:public_repo read:user}") String defaultScope) {
        this(factory, httpClient, authProvider, clientId, defaultScope, true);
    }

    GitHubDeviceFlowService(RepositoryFactory factory,
                            GitHubDeviceFlowHttpClient httpClient,
                            GitHubDeviceFlowAuthProvider authProvider,
                            String clientId,
                            String defaultScope,
                            boolean unused) {
        this.sessionRepository = factory.create(GitHubDeviceFlowSession.class);
        this.httpClient = httpClient;
        this.authProvider = authProvider;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.defaultScope = defaultScope == null || defaultScope.isBlank() ? "public_repo read:user" : defaultScope.trim();
    }

    public Map<String, Object> start(String username, String requestedScope) {
        log.debug("ENTER start: username={}", username);
        if (username == null || username.isBlank()) {
            return Map.of("status", "error", "message", "Authenticated user is required");
        }
        if (clientId.isBlank()) {
            return Map.of("status", "error", "message", "GitHub Device Flow client id is not configured");
        }

        String scope = requestedScope == null || requestedScope.isBlank() ? defaultScope : requestedScope.trim();
        GitHubDeviceFlowHttpClient.DeviceCodeResponse response = httpClient.requestDeviceCode(clientId, scope);
        if (response.deviceCode() == null || response.deviceCode().isBlank()) {
            return Map.of("status", "error", "message", "GitHub did not return a device_code");
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + (Math.max(response.expiresIn(), 60) * 1000L);
        String flowId = UUID.randomUUID().toString();
        GitHubDeviceFlowSession session = new GitHubDeviceFlowSession(
                flowId,
                username,
                response.deviceCode(),
                response.userCode(),
                response.verificationUri(),
                response.verificationUriComplete(),
                Math.max(response.interval(), 5),
                expiresAt,
                now,
                now,
                "PENDING",
                "",
                "",
                0L);
        sessionRepository.save(session);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "connect_required");
        out.put("flowId", flowId);
        out.put("userCode", session.userCode());
        out.put("verificationUri", session.verificationUri());
        out.put("verificationUriComplete", session.verificationUriComplete());
        out.put("intervalSeconds", session.intervalSeconds());
        out.put("expiresAt", session.expiresAt());
        out.put("scope", scope);
        log.info("GitHub Device Flow started [username={}, flowId={}]", username, flowId);
        log.debug("EXIT start: flowId={}", flowId);
        return out;
    }

    public Map<String, Object> poll(String username, String flowId) {
        log.debug("ENTER poll: username={}, flowId={}", username, flowId);
        if (username == null || username.isBlank()) {
            return Map.of("status", "error", "message", "Authenticated user is required");
        }
        GitHubDeviceFlowSession session = sessionRepository.get(flowId);
        if (session == null || !username.equals(session.userUuid())) {
            return Map.of("status", "error", "message", "Invalid flow id");
        }

        // If we already completed this flow for the user, return approved immediately.
        if ("APPROVED".equalsIgnoreCase(session.status()) || authProvider.isAuthenticated(username)) {
            String login = authProvider.getConnectedExternalUsername(username);
            return Map.of(
                    "status", "approved",
                    "connected", true,
                    "provider", authProvider.providerName(),
                    "githubLogin", login == null ? "" : login,
                    "tokenExpiresAt", authProvider.getAccessTokenExpiresAt(username));
        }

        long now = System.currentTimeMillis();
        if (session.expiresAt() <= now) {
            GitHubDeviceFlowSession expired = updateSession(session, "EXPIRED", "expired_token", "", 0L);
            sessionRepository.save(expired);
            return Map.of("status", "expired", "message", "Device flow challenge expired");
        }

        GitHubDeviceFlowHttpClient.AccessTokenPollResponse response =
                httpClient.pollAccessToken(clientId, session.deviceCode());

        return switch (response.status()) {
            case PENDING -> {
                GitHubDeviceFlowSession updated = updateSession(session, "PENDING", "", "", 0L);
                sessionRepository.save(updated);
                yield Map.of(
                        "status", "pending",
                        "flowId", flowId,
                        "intervalSeconds", updated.intervalSeconds(),
                        "expiresAt", updated.expiresAt());
            }
            case SLOW_DOWN -> {
                int nextInterval = Math.max(session.intervalSeconds(), 5) + 5;
                GitHubDeviceFlowSession updated = new GitHubDeviceFlowSession(
                        session.uuid(),
                        session.userUuid(),
                        session.deviceCode(),
                        session.userCode(),
                        session.verificationUri(),
                        session.verificationUriComplete(),
                        nextInterval,
                        session.expiresAt(),
                        session.createdAt(),
                        now,
                        "PENDING",
                        "slow_down",
                        session.githubLogin(),
                        session.tokenExpiresAt());
                sessionRepository.save(updated);
                yield Map.of(
                        "status", "pending",
                        "flowId", flowId,
                        "intervalSeconds", nextInterval,
                        "expiresAt", updated.expiresAt());
            }
            case DECLINED -> {
                GitHubDeviceFlowSession updated = updateSession(session, "DECLINED", "access_denied", "", 0L);
                sessionRepository.save(updated);
                yield Map.of("status", "declined", "message", "User denied authorization");
            }
            case EXPIRED -> {
                GitHubDeviceFlowSession updated = updateSession(session, "EXPIRED", "expired_token", "", 0L);
                sessionRepository.save(updated);
                yield Map.of("status", "expired", "message", "Device flow challenge expired");
            }
            case ERROR -> {
                String error = response.error() == null ? "unknown_error" : response.error();
                GitHubDeviceFlowSession updated = updateSession(session, "ERROR", error, "", 0L);
                sessionRepository.save(updated);
                yield Map.of("status", "error", "message", "GitHub token request failed: " + error);
            }
            case APPROVED -> handleApproved(username, session, response);
        };
    }

    public Map<String, Object> status(String username) {
        log.debug("ENTER status: username={}", username);
        if (username == null || username.isBlank()) {
            return Map.of("status", "error", "message", "Authenticated user is required");
        }
        boolean connected = authProvider.isAuthenticated(username);
        String login = connected ? authProvider.getConnectedExternalUsername(username) : "";
        long accessTokenExpiresAt = connected ? authProvider.getAccessTokenExpiresAt(username) : 0L;
        long refreshTokenExpiresAt = connected ? authProvider.getRefreshTokenExpiresAt(username) : 0L;
        boolean refreshCapable = connected && authProvider.hasRefreshToken(username)
            && (refreshTokenExpiresAt == 0L || refreshTokenExpiresAt > System.currentTimeMillis());
        Map<String, Object> result = Map.of(
                "status", "ok",
                "connected", connected,
                "provider", authProvider.providerName(),
            "githubLogin", login == null ? "" : login,
            "accessTokenExpiresAt", accessTokenExpiresAt,
            "refreshTokenExpiresAt", refreshTokenExpiresAt,
            "refreshCapable", refreshCapable);
        log.debug("EXIT status: username={}, connected={}", username, connected);
        return result;
    }

    public Map<String, Object> disconnect(String username) {
        log.debug("ENTER disconnect: username={}", username);
        if (username == null || username.isBlank()) {
            return Map.of("status", "error", "message", "Authenticated user is required");
        }
        authProvider.clearAuthorization(username);
        log.info("GitHub Device Flow disconnected [username={}]", username);
        log.debug("EXIT disconnect");
        return Map.of("status", "ok", "connected", false);
    }

    private Map<String, Object> handleApproved(String username,
                                                GitHubDeviceFlowSession session,
                                                GitHubDeviceFlowHttpClient.AccessTokenPollResponse response) {
        long now = System.currentTimeMillis();
        String token = response.accessToken() == null ? "" : response.accessToken().trim();
        if (token.isBlank()) {
            GitHubDeviceFlowSession errored = updateSession(session, "ERROR", "missing_access_token", "", 0L);
            sessionRepository.save(errored);
            return Map.of("status", "error", "message", "GitHub did not return an access token");
        }

        String githubLogin = "";
        try {
            githubLogin = httpClient.fetchUserLogin(token);
        } catch (RuntimeException ex) {
            log.warn("GitHub login lookup failed; continuing with empty login [username={}]: {}",
                    username, ex.getMessage());
        }

        long tokenExpiresAt = response.expiresIn() > 0
                ? now + (response.expiresIn() * 1000L)
                : 0L;
        String refreshToken = response.refreshToken() == null ? "" : response.refreshToken().trim();
        long refreshTokenExpiresAt = response.refreshTokenExpiresIn() > 0
            ? now + (response.refreshTokenExpiresIn() * 1000L)
            : 0L;
        authProvider.storeAuthorization(
            username,
            githubLogin,
            token,
            tokenExpiresAt,
            refreshToken,
            refreshTokenExpiresAt);

        GitHubDeviceFlowSession approved = new GitHubDeviceFlowSession(
                session.uuid(),
                session.userUuid(),
                session.deviceCode(),
                session.userCode(),
                session.verificationUri(),
                session.verificationUriComplete(),
                session.intervalSeconds(),
                session.expiresAt(),
                session.createdAt(),
                now,
                "APPROVED",
                "",
                githubLogin,
                tokenExpiresAt);
        sessionRepository.save(approved);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "approved");
        out.put("connected", true);
        out.put("provider", authProvider.providerName());
        out.put("githubLogin", githubLogin);
        out.put("tokenExpiresAt", tokenExpiresAt);
        log.info("GitHub Device Flow approved [username={}, flowId={}, githubLogin={}]",
                username, session.uuid(), githubLogin);
        return out;
    }

    private static GitHubDeviceFlowSession updateSession(GitHubDeviceFlowSession session,
                                                          String status,
                                                          String error,
                                                          String githubLogin,
                                                          long tokenExpiresAt) {
        long now = System.currentTimeMillis();
        return new GitHubDeviceFlowSession(
                session.uuid(),
                session.userUuid(),
                session.deviceCode(),
                session.userCode(),
                session.verificationUri(),
                session.verificationUriComplete(),
                session.intervalSeconds(),
                session.expiresAt(),
                session.createdAt(),
                now,
                status,
                error,
                githubLogin,
                tokenExpiresAt);
    }
}
