package sh.vork.github.auth;

public interface GitHubDeviceFlowHttpClient {

    DeviceCodeResponse requestDeviceCode(String clientId, String scope);

    AccessTokenPollResponse pollAccessToken(String clientId, String deviceCode);

    AccessTokenPollResponse refreshAccessToken(String clientId, String refreshToken);

    String fetchUserLogin(String accessToken);

    record DeviceCodeResponse(
            String deviceCode,
            String userCode,
            String verificationUri,
            String verificationUriComplete,
            int expiresIn,
            int interval
    ) {
    }

    record AccessTokenPollResponse(
            PollStatus status,
            String accessToken,
            int expiresIn,
            String refreshToken,
            int refreshTokenExpiresIn,
            String error,
            int interval
    ) {
    }

    enum PollStatus {
        APPROVED,
        PENDING,
        SLOW_DOWN,
        DECLINED,
        EXPIRED,
        ERROR
    }
}
