package sh.vork.github.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import sh.vork.security.SecureCredentialStore;
import sh.vork.security.UserService;

/**
 * Stores and resolves GitHub OAuth Device Flow authorization state per user.
 */
@Service
public class GitHubDeviceFlowAuthProvider implements ContributionAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(GitHubDeviceFlowAuthProvider.class);

    static final String PROVIDER_NAME = "github-device-flow";
    static final String ACCESS_TOKEN_KEY = "github.deviceflow.access-token";
    static final String EXPIRES_AT_KEY = "github.deviceflow.expires-at";
    static final String REFRESH_TOKEN_KEY = "github.deviceflow.refresh-token";
    static final String REFRESH_EXPIRES_AT_KEY = "github.deviceflow.refresh-expires-at";
    static final String EXTERNAL_USERNAME_KEY = "github.deviceflow.external-username";

    private final SecureCredentialStore credentialStore;
    private final UserService userService;
    private final GitHubDeviceFlowHttpClient httpClient;
    private final String clientId;

    public GitHubDeviceFlowAuthProvider(SecureCredentialStore credentialStore,
                                        UserService userService,
                                        GitHubDeviceFlowHttpClient httpClient,
                                        @Value("${vork.github.oauth.device.client-id:${GITHUB_OAUTH_CLIENT_ID:}}") String clientId) {
        this.credentialStore = credentialStore;
        this.userService = userService;
        this.httpClient = httpClient;
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAuthenticated(String username) {
        log.debug("ENTER isAuthenticated: username={}", username);
        String accessToken = credentialStore.getSecretForUser(username, ACCESS_TOKEN_KEY);
        if (accessToken == null || accessToken.isBlank()) {
            log.debug("EXIT isAuthenticated: username={}, authenticated=false", username);
            return false;
        }

        long expiresAt = parseLong(credentialStore.getSecretForUser(username, EXPIRES_AT_KEY), 0L);
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
            log.warn("GitHub device auth token expired [username={}, expiresAt={}]", username, expiresAt);
            clearAuthorization(username);
            log.debug("EXIT isAuthenticated: username={}, authenticated=false", username);
            return false;
        }

        log.debug("EXIT isAuthenticated: username={}, authenticated=true", username);
        return true;
    }

    @Override
    public ContributionAuthToken requireToken(String username) {
        log.debug("ENTER requireToken: username={}", username);
        String accessToken = credentialStore.getSecretForUser(username, ACCESS_TOKEN_KEY);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("GitHub is not connected for this user.");
        }

        long expiresAt = parseLong(credentialStore.getSecretForUser(username, EXPIRES_AT_KEY), 0L);
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
            accessToken = refreshAccessTokenIfPossible(username);
            expiresAt = parseLong(credentialStore.getSecretForUser(username, EXPIRES_AT_KEY), 0L);
        }

        String externalUsername = credentialStore.getSecretForUser(username, EXTERNAL_USERNAME_KEY);
        ContributionAuthToken token = new ContributionAuthToken(
                providerName(),
                username,
                externalUsername == null ? "" : externalUsername,
                accessToken,
                expiresAt);
        log.debug("EXIT requireToken: username={}, externalUsername={}, expiresAt={}",
                username, token.externalUsername(), token.expiresAt());
        return token;
    }

    public void storeAuthorization(String username,
                                   String externalUsername,
                                   String accessToken,
                                   long expiresAt,
                                   String refreshToken,
                                   long refreshTokenExpiresAt) {
        log.debug("ENTER storeAuthorization: username={}, externalUsername={}, expiresAt={}",
                username, externalUsername, expiresAt);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Access token must not be blank.");
        }

        credentialStore.saveSecretForUser(username, ACCESS_TOKEN_KEY, accessToken);
        credentialStore.saveSecretForUser(username, EXPIRES_AT_KEY, String.valueOf(expiresAt));
        credentialStore.saveSecretForUser(username, REFRESH_TOKEN_KEY,
            refreshToken == null ? "" : refreshToken);
        credentialStore.saveSecretForUser(username, REFRESH_EXPIRES_AT_KEY,
            String.valueOf(refreshTokenExpiresAt));
        credentialStore.saveSecretForUser(username, EXTERNAL_USERNAME_KEY,
                externalUsername == null ? "" : externalUsername);
        log.info("GitHub device flow authorization stored [username={}, externalUsername={}]",
                username, externalUsername);
        log.debug("EXIT storeAuthorization");
    }

    public void clearAuthorization(String username) {
        log.debug("ENTER clearAuthorization: username={}", username);
        var user = userService.getRequiredEnabledUser(username);
        credentialStore.deleteSecret(user, ACCESS_TOKEN_KEY);
        credentialStore.deleteSecret(user, EXPIRES_AT_KEY);
        credentialStore.deleteSecret(user, REFRESH_TOKEN_KEY);
        credentialStore.deleteSecret(user, REFRESH_EXPIRES_AT_KEY);
        credentialStore.deleteSecret(user, EXTERNAL_USERNAME_KEY);
        log.info("GitHub device flow authorization cleared [username={}]", username);
        log.debug("EXIT clearAuthorization");
    }

    private String refreshAccessTokenIfPossible(String username) {
        if (clientId.isBlank()) {
            clearAuthorization(username);
            throw new IllegalStateException("GitHub authorization has expired. Reconnect via Device Flow.");
        }

        String refreshToken = credentialStore.getSecretForUser(username, REFRESH_TOKEN_KEY);
        if (refreshToken == null || refreshToken.isBlank()) {
            clearAuthorization(username);
            throw new IllegalStateException("GitHub authorization has expired. Reconnect via Device Flow.");
        }

        long refreshExpiresAt = parseLong(credentialStore.getSecretForUser(username, REFRESH_EXPIRES_AT_KEY), 0L);
        if (refreshExpiresAt > 0 && refreshExpiresAt <= System.currentTimeMillis()) {
            clearAuthorization(username);
            throw new IllegalStateException("GitHub authorization refresh token has expired. Reconnect via Device Flow.");
        }

        GitHubDeviceFlowHttpClient.AccessTokenPollResponse refreshed = httpClient.refreshAccessToken(clientId, refreshToken);
        if (refreshed.status() != GitHubDeviceFlowHttpClient.PollStatus.APPROVED
                || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
            clearAuthorization(username);
            throw new IllegalStateException("GitHub authorization refresh failed. Reconnect via Device Flow.");
        }

        long now = System.currentTimeMillis();
        long newAccessExpiresAt = refreshed.expiresIn() > 0 ? now + (refreshed.expiresIn() * 1000L) : 0L;
        String newRefreshToken = refreshed.refreshToken() == null || refreshed.refreshToken().isBlank()
                ? refreshToken
                : refreshed.refreshToken();
        long newRefreshExpiresAt = refreshed.refreshTokenExpiresIn() > 0
                ? now + (refreshed.refreshTokenExpiresIn() * 1000L)
                : refreshExpiresAt;

        String externalUsername = credentialStore.getSecretForUser(username, EXTERNAL_USERNAME_KEY);
        storeAuthorization(username,
                externalUsername == null ? "" : externalUsername,
                refreshed.accessToken(),
                newAccessExpiresAt,
                newRefreshToken,
                newRefreshExpiresAt);
        log.info("GitHub access token refreshed [username={}]", username);
        return refreshed.accessToken();
    }

    public String getConnectedExternalUsername(String username) {
        return credentialStore.getSecretForUser(username, EXTERNAL_USERNAME_KEY);
    }

    public long getAccessTokenExpiresAt(String username) {
        return parseLong(credentialStore.getSecretForUser(username, EXPIRES_AT_KEY), 0L);
    }

    public long getRefreshTokenExpiresAt(String username) {
        return parseLong(credentialStore.getSecretForUser(username, REFRESH_EXPIRES_AT_KEY), 0L);
    }

    public boolean hasRefreshToken(String username) {
        String refreshToken = credentialStore.getSecretForUser(username, REFRESH_TOKEN_KEY);
        return refreshToken != null && !refreshToken.isBlank();
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
