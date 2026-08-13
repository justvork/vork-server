package sh.vork.github.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@ExtendWith(MockitoExtension.class)
class GitHubDeviceFlowServiceTest {

    @Mock
    private RepositoryFactory repositoryFactory;

    @Mock
    private DatabaseRepository<GitHubDeviceFlowSession> sessionRepository;

    @Mock
    private GitHubDeviceFlowHttpClient httpClient;

    @Mock
    private GitHubDeviceFlowAuthProvider authProvider;

    private GitHubDeviceFlowService service;

    @BeforeEach
    void setUp() {
        when(repositoryFactory.create(GitHubDeviceFlowSession.class)).thenReturn(sessionRepository);
        service = new GitHubDeviceFlowService(
                repositoryFactory,
                httpClient,
                authProvider,
                "client-id-123",
                "public_repo read:user",
                true);
    }

    @Test
    void startReturnsConnectRequiredAndPersistsSession() {
        when(httpClient.requestDeviceCode(anyString(), anyString()))
                .thenReturn(new GitHubDeviceFlowHttpClient.DeviceCodeResponse(
                        "device-code",
                        "user-code",
                        "https://github.com/login/device",
                        "https://github.com/login/device?user_code=user-code",
                        900,
                        5));

        Map<String, Object> result = service.start("alice", null);

        assertEquals("connect_required", result.get("status"));
        assertEquals("user-code", result.get("userCode"));
        assertTrue(String.valueOf(result.get("flowId")).length() > 10);
        verify(sessionRepository).save(any(GitHubDeviceFlowSession.class));
    }

    @Test
    void pollApprovedStoresAuthorizationAndReturnsApprovedStatus() {
        GitHubDeviceFlowSession existing = new GitHubDeviceFlowSession(
                "flow-1",
                "alice",
                "device-code",
                "user-code",
                "https://github.com/login/device",
                "https://github.com/login/device?user_code=user-code",
                5,
                System.currentTimeMillis() + 60_000,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "PENDING",
                "",
                "",
                0L);

        when(sessionRepository.get("flow-1")).thenReturn(existing);
        when(httpClient.pollAccessToken(anyString(), anyString()))
                .thenReturn(new GitHubDeviceFlowHttpClient.AccessTokenPollResponse(
                        GitHubDeviceFlowHttpClient.PollStatus.APPROVED,
                        "access-token-123",
                        3600,
                "refresh-token-123",
                86_400,
                        "",
                        0));
        when(httpClient.fetchUserLogin("access-token-123")).thenReturn("octocat");

        Map<String, Object> result = service.poll("alice", "flow-1");

        assertEquals("approved", result.get("status"));
        assertEquals("octocat", result.get("githubLogin"));
        verify(authProvider).storeAuthorization(anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong());
        verify(sessionRepository).save(any(GitHubDeviceFlowSession.class));
    }

        @Test
        void pollReturnsApprovedImmediatelyWhenAlreadyAuthenticated() {
                GitHubDeviceFlowSession existing = new GitHubDeviceFlowSession(
                                "flow-2",
                                "alice",
                                "device-code",
                                "user-code",
                                "https://github.com/login/device",
                                "https://github.com/login/device?user_code=user-code",
                                5,
                                System.currentTimeMillis() + 60_000,
                                System.currentTimeMillis(),
                                System.currentTimeMillis(),
                                "PENDING",
                                "",
                                "",
                                0L);

                when(sessionRepository.get("flow-2")).thenReturn(existing);
                when(authProvider.isAuthenticated("alice")).thenReturn(true);
                when(authProvider.getConnectedExternalUsername("alice")).thenReturn("octocat");
                when(authProvider.providerName()).thenReturn("github-device-flow");
                when(authProvider.getAccessTokenExpiresAt("alice")).thenReturn(System.currentTimeMillis() + 120_000);

                Map<String, Object> result = service.poll("alice", "flow-2");

                assertEquals("approved", result.get("status"));
                assertEquals("octocat", result.get("githubLogin"));
                verify(httpClient, never()).pollAccessToken(anyString(), anyString());
        }

        @Test
        void statusIncludesTokenHealthMetadata() {
                when(authProvider.isAuthenticated("alice")).thenReturn(true);
                when(authProvider.getConnectedExternalUsername("alice")).thenReturn("octocat");
                when(authProvider.providerName()).thenReturn("github-device-flow");
                when(authProvider.getAccessTokenExpiresAt("alice")).thenReturn(System.currentTimeMillis() + 60_000);
                when(authProvider.getRefreshTokenExpiresAt("alice")).thenReturn(System.currentTimeMillis() + 3_600_000);
                when(authProvider.hasRefreshToken("alice")).thenReturn(true);

                Map<String, Object> result = service.status("alice");

                assertEquals("ok", result.get("status"));
                assertEquals(true, result.get("connected"));
                assertEquals("octocat", result.get("githubLogin"));
                assertEquals(true, result.get("refreshCapable"));
        }
}
