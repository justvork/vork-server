package sh.vork.github.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sh.vork.security.SecureCredentialStore;
import sh.vork.security.UserService;

@ExtendWith(MockitoExtension.class)
class GitHubDeviceFlowAuthProviderTest {

    @Mock
    private SecureCredentialStore credentialStore;

    @Mock
    private UserService userService;

    @Mock
    private GitHubDeviceFlowHttpClient httpClient;

    private GitHubDeviceFlowAuthProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GitHubDeviceFlowAuthProvider(credentialStore, userService, httpClient, "client-id-123");
    }

    @Test
    void isAuthenticatedReturnsFalseWhenNoToken() {
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.ACCESS_TOKEN_KEY)).thenReturn(null);
        assertFalse(provider.isAuthenticated("alice"));
    }

    @Test
    void isAuthenticatedReturnsTrueWhenTokenPresentAndUnexpired() {
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.ACCESS_TOKEN_KEY)).thenReturn("token");
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.EXPIRES_AT_KEY))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 30_000));

        assertTrue(provider.isAuthenticated("alice"));
    }

    @Test
    void requireTokenReturnsContributionAuthToken() {
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.ACCESS_TOKEN_KEY)).thenReturn("token-123");
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.EXPIRES_AT_KEY))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 30_000));
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.EXTERNAL_USERNAME_KEY)).thenReturn("octocat");

        ContributionAuthToken token = provider.requireToken("alice");

        assertEquals("github-device-flow", token.provider());
        assertEquals("alice", token.localUsername());
        assertEquals("octocat", token.externalUsername());
        assertEquals("token-123", token.accessToken());
    }

    @Test
    void requireTokenThrowsWhenDisconnected() {
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.ACCESS_TOKEN_KEY)).thenReturn("");
        assertThrows(IllegalStateException.class, () -> provider.requireToken("alice"));
    }

    @Test
    void requireTokenRefreshesExpiredTokenWhenRefreshTokenAvailable() {
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.ACCESS_TOKEN_KEY)).thenReturn("expired-token");
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.EXPIRES_AT_KEY))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 30_000))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 30_000));
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.REFRESH_TOKEN_KEY))
                .thenReturn("refresh-token-1");
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.REFRESH_EXPIRES_AT_KEY))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 90_000));
        when(credentialStore.getSecretForUser("alice", GitHubDeviceFlowAuthProvider.EXTERNAL_USERNAME_KEY))
                .thenReturn("octocat");

        when(httpClient.refreshAccessToken("client-id-123", "refresh-token-1"))
                .thenReturn(new GitHubDeviceFlowHttpClient.AccessTokenPollResponse(
                        GitHubDeviceFlowHttpClient.PollStatus.APPROVED,
                        "new-access-token",
                        3600,
                        "new-refresh-token",
                        86400,
                        "",
                        0));

        ContributionAuthToken token = provider.requireToken("alice");

        assertEquals("new-access-token", token.accessToken());
        verify(credentialStore).saveSecretForUser("alice", GitHubDeviceFlowAuthProvider.ACCESS_TOKEN_KEY, "new-access-token");
        verify(credentialStore).saveSecretForUser("alice", GitHubDeviceFlowAuthProvider.REFRESH_TOKEN_KEY, "new-refresh-token");
    }
}
