package sh.vork.notification.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Thin HTTP client for the Slack Web API.
 *
 * <p>Handles {@code chat.postMessage}, {@code apps.connections.open},
 * and {@code conversations.info}.  All methods throw a {@link SlackApiException}
 * when Slack returns {@code "ok": false} or when the HTTP call itself fails.
 */
@Component
public class SlackApiClient {

    private static final Logger log = LoggerFactory.getLogger(SlackApiClient.class);

    private static final String SLACK_API = "https://slack.com/api/";

    private final HttpClient    httpClient;
    private final ObjectMapper  objectMapper;

    public SlackApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newHttpClient();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Posts a plain-text message to a Slack channel or DM.
     *
     * @param botToken  {@code xoxb-…} bot token
     * @param channelId Slack channel / conversation ID
     * @param text      message body
     */
    public void sendMessage(String botToken, String channelId, String text) {
        log.debug("ENTER sendMessage: [channel={}]", channelId);
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "channel", channelId,
                    "text",    text));
        } catch (Exception e) {
            throw new SlackApiException("Failed to serialise sendMessage payload", e);
        }

        Map<String, Object> response = postJson(botToken, "chat.postMessage", body);
        requireOk(response, "chat.postMessage");
        log.debug("EXIT sendMessage: [channel={}, ts={}]", channelId, response.get("ts"));
    }

    /**
     * Opens a Socket Mode WebSocket connection and returns the one-time WSS URL.
     *
     * @param appToken {@code xapp-…} app-level token
     * @return WSS URL (valid for approximately 30 minutes)
     */
    public String openSocketModeConnection(String appToken) {
        log.debug("ENTER openSocketModeConnection");
        String body = ""; // apps.connections.open requires no body
        Map<String, Object> response = postJson(appToken, "apps.connections.open", body);
        requireOk(response, "apps.connections.open");

        String url = (String) response.get("url");
        if (url == null || url.isBlank()) {
            throw new SlackApiException("apps.connections.open returned no URL");
        }
        log.debug("EXIT openSocketModeConnection: URL obtained");
        return url;
    }

    /**
     * Retrieves the display name of a channel or DM conversation.
     *
     * @param botToken  {@code xoxb-…} bot token
     * @param channelId Slack conversation ID
     * @return channel name (or empty string if unavailable)
     */
    public String getChannelName(String botToken, String channelId) {
        log.debug("ENTER getChannelName: [channel={}]", channelId);
        try {
            URI uri = URI.create(SLACK_API + "conversations.info?channel=" + channelId);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + botToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(
                    resp.body(), new TypeReference<>() {});
            if (Boolean.TRUE.equals(result.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> channel = (Map<String, Object>) result.get("channel");
                if (channel != null) {
                    String name = (String) channel.get("name");
                    if (name != null) return name;
                }
            }
        } catch (Exception e) {
            log.warn("getChannelName failed [channel={}]: {}", channelId, e.getMessage());
        }
        return channelId; // fallback to ID
    }

    /**
     * Downloads a private Slack file and returns the raw bytes.
     *
     * <p>Slack private file URLs require an {@code Authorization: Bearer} header;
     * cookies or query-string tokens are not accepted.
     *
     * @param botToken {@code xoxb-…} bot token
     * @param fileUrl  {@code url_private} value from a Slack file object
     * @return raw file bytes, or {@code null} if the download failed
     */
    public byte[] downloadFile(String botToken, String fileUrl) {
        log.debug("ENTER downloadFile: [url={}]", fileUrl);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(fileUrl))
                    .header("Authorization", "Bearer " + botToken)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Slack file download failed [status={}, url={}]", resp.statusCode(), fileUrl);
                return null;
            }
            log.debug("EXIT downloadFile: [bytes={}]", resp.body().length);
            return resp.body();
        } catch (Exception e) {
            log.warn("Slack downloadFile failed [url={}, error={}]", fileUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Uploads a binary file to a channel/DM using {@code files.upload}.
     *
     * @param botToken       {@code xoxb-...} bot token
     * @param channelId      Slack conversation ID
     * @param fileName       file name shown in Slack
     * @param mimeType       MIME type for the uploaded file
     * @param bytes          file content
     * @param initialComment optional message shown with the file
     */
    public void sendFile(String botToken,
                         String channelId,
                         String fileName,
                         String mimeType,
                         byte[] bytes,
                         String initialComment) {
        log.debug("ENTER sendFile: [channel={}, file={}, bytes={}]",
                channelId, fileName, bytes == null ? 0 : bytes.length);
        if (bytes == null || bytes.length == 0) {
            throw new SlackApiException("Cannot upload empty file content");
        }

        String boundary = "----vork-slack-" + UUID.randomUUID();
        byte[] body = buildMultipartFileUpload(boundary, channelId, fileName, mimeType, bytes, initialComment);

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(SLACK_API + "files.upload"))
                    .header("Authorization", "Bearer " + botToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new SlackApiException("Slack files.upload returned HTTP " + resp.statusCode());
            }
            Map<String, Object> response = objectMapper.readValue(resp.body(), new TypeReference<>() {});
            requireOk(response, "files.upload");
            log.debug("EXIT sendFile: [channel={}, file={}]", channelId, fileName);
        } catch (SlackApiException e) {
            throw e;
        } catch (Exception e) {
            throw new SlackApiException("Slack file upload failed", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> postJson(String token, String method, String jsonBody) {
        try {
            URI uri = URI.create(SLACK_API + method);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new SlackApiException(
                        "Slack API " + method + " returned HTTP " + resp.statusCode());
            }
            return objectMapper.readValue(resp.body(), new TypeReference<>() {});
        } catch (SlackApiException e) {
            throw e;
        } catch (Exception e) {
            throw new SlackApiException("Slack API call failed: " + method, e);
        }
    }

    private static void requireOk(Map<String, Object> response, String method) {
        if (!Boolean.TRUE.equals(response.get("ok"))) {
            String error = (String) response.getOrDefault("error", "unknown_error");
            throw new SlackApiException("Slack " + method + " error: " + error);
        }
    }

    private static byte[] buildMultipartFileUpload(String boundary,
                                                   String channelId,
                                                   String fileName,
                                                   String mimeType,
                                                   byte[] fileBytes,
                                                   String initialComment) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeFormPart(out, boundary, "channels", channelId);
            writeFormPart(out, boundary, "filename", fileName);
            if (initialComment != null && !initialComment.isBlank()) {
                writeFormPart(out, boundary, "initial_comment", initialComment);
            }

            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + escapeHeaderValue(fileName) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + (mimeType == null || mimeType.isBlank()
                    ? "application/octet-stream" : mimeType) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));

            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new SlackApiException("Failed to build multipart payload", e);
        }
    }

    private static void writeFormPart(ByteArrayOutputStream out,
                                      String boundary,
                                      String name,
                                      String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeHeaderValue(String value) {
        return value == null ? "attachment" : value.replace("\"", "'").replace("\n", "").replace("\r", "");
    }
}
