package sh.vork.github.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class DefaultGitHubDeviceFlowHttpClient implements GitHubDeviceFlowHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultGitHubDeviceFlowHttpClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DefaultGitHubDeviceFlowHttpClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public DeviceCodeResponse requestDeviceCode(String clientId, String scope) {
        log.debug("ENTER requestDeviceCode: scope={}", scope);
        String body = "client_id=" + encode(clientId) + "&scope=" + encode(scope);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://github.com/login/device/code"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        JsonNode json = sendJson(request);
        DeviceCodeResponse response = new DeviceCodeResponse(
                json.path("device_code").asText(""),
                json.path("user_code").asText(""),
                json.path("verification_uri").asText(""),
                json.path("verification_uri_complete").asText(""),
                json.path("expires_in").asInt(0),
                json.path("interval").asInt(5));
        log.debug("EXIT requestDeviceCode: expiresIn={}, interval={}", response.expiresIn(), response.interval());
        return response;
    }

    @Override
    public AccessTokenPollResponse pollAccessToken(String clientId, String deviceCode) {
        String body = "client_id=" + encode(clientId)
                + "&device_code=" + encode(deviceCode)
                + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        JsonNode json = sendJson(request);
        String error = json.path("error").asText("");
        if (!error.isBlank()) {
            PollStatus status = switch (error) {
                case "authorization_pending" -> PollStatus.PENDING;
                case "slow_down" -> PollStatus.SLOW_DOWN;
                case "access_denied" -> PollStatus.DECLINED;
                case "expired_token" -> PollStatus.EXPIRED;
                default -> PollStatus.ERROR;
            };
                return new AccessTokenPollResponse(status, "", 0, "", 0, error, json.path("interval").asInt(0));
        }

        return new AccessTokenPollResponse(
                PollStatus.APPROVED,
                json.path("access_token").asText(""),
                json.path("expires_in").asInt(0),
                json.path("refresh_token").asText(""),
                json.path("refresh_token_expires_in").asInt(0),
                "",
                json.path("interval").asInt(0));
    }

            @Override
            public AccessTokenPollResponse refreshAccessToken(String clientId, String refreshToken) {
            log.debug("ENTER refreshAccessToken");
            String body = "client_id=" + encode(clientId)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + encode(refreshToken);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            JsonNode json = sendJson(request);
            String error = json.path("error").asText("");
            if (!error.isBlank()) {
                log.warn("GitHub refresh grant returned error: {}", error);
                return new AccessTokenPollResponse(PollStatus.ERROR, "", 0, "", 0, error, 0);
            }

            AccessTokenPollResponse response = new AccessTokenPollResponse(
                PollStatus.APPROVED,
                json.path("access_token").asText(""),
                json.path("expires_in").asInt(0),
                json.path("refresh_token").asText(""),
                json.path("refresh_token_expires_in").asInt(0),
                "",
                0);
            log.debug("EXIT refreshAccessToken: expiresIn={}, refreshExpiresIn={}",
                response.expiresIn(), response.refreshTokenExpiresIn());
            return response;
            }

    @Override
    public String fetchUserLogin(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/user"))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        JsonNode json = sendJson(request);
        return json.path("login").asText("");
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("GitHub request failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub request failed: " + ex.getMessage(), ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
