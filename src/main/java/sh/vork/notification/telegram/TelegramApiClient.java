package sh.vork.notification.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin wrapper around the Telegram Bot HTTP API.
 *
 * <p>All methods are fire-and-forget: errors are logged but not rethrown
 * so that a Telegram API hiccup never crashes the caller's flow.
 */
@Service
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final int    MAX_MSG_LEN = 4096;
    private static final int    MAX_CB_TEXT = 200;

    /** A single button in an inline keyboard row. */
    public record InlineButton(String text, String callbackData) {}

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient;

    public TelegramApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Sends a plain-text message to the given chat. */
    public void sendText(String botToken, String chatId, String text) {
        post(botToken, "sendMessage",
                Map.of("chat_id", chatId, "text", truncate(text, MAX_MSG_LEN)));
    }

    /** Sends a MarkdownV2-formatted message. */
    public void sendTextMarkdownV2(String botToken, String chatId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id",    chatId);
        payload.put("text",       truncate(text, MAX_MSG_LEN));
        payload.put("parse_mode", "MarkdownV2");
        post(botToken, "sendMessage", payload);
    }

    /**
     * Sends a message with an inline keyboard.
     *
     * @param keyboard list of rows, each row is a list of {@link InlineButton}s
     */
    public void sendWithInlineKeyboard(String botToken, String chatId, String text,
                                        List<List<InlineButton>> keyboard) {
        List<List<Map<String, String>>> tgKeyboard = keyboard.stream()
                .map(row -> row.stream()
                        .map(btn -> Map.of("text", btn.text(), "callback_data", btn.callbackData()))
                        .toList())
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id",      chatId);
        payload.put("text",         truncate(text, MAX_MSG_LEN));
        payload.put("reply_markup", Map.of("inline_keyboard", tgKeyboard));
        post(botToken, "sendMessage", payload);
    }

    /** Sends a MarkdownV2-formatted message with an inline keyboard. */
    public void sendWithInlineKeyboardMarkdownV2(String botToken, String chatId, String text,
                                                  List<List<InlineButton>> keyboard) {
        List<List<Map<String, String>>> tgKeyboard = keyboard.stream()
                .map(row -> row.stream()
                        .map(btn -> Map.of("text", btn.text(), "callback_data", btn.callbackData()))
                        .toList())
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id",      chatId);
        payload.put("text",         truncate(text, MAX_MSG_LEN));
        payload.put("parse_mode",   "MarkdownV2");
        payload.put("reply_markup", Map.of("inline_keyboard", tgKeyboard));
        post(botToken, "sendMessage", payload);
    }

    /**    /**
     * Downloads a Telegram file by its {@code file_id} and returns the raw bytes.
     *
     * <p>Performs two requests: {@code getFile} to resolve the server-side path, then
     * a direct download from {@code https://api.telegram.org/file/bot{token}/{path}}.
     *
     * @param botToken bot API token
     * @param fileId   the Telegram {@code file_id}
     * @return raw file bytes, or {@code null} if the download failed
     */
    public byte[] downloadFile(String botToken, String fileId) {
        log.debug("ENTER downloadFile: [fileId={}]", fileId);
        try {
            // Step 1: resolve file_path
            String getFileUrl = API_BASE + botToken + "/getFile?file_id=" + fileId;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(getFileUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> metaResp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(metaResp.body());
            String filePath = root.path("result").path("file_path").asText(null);
            if (filePath == null || filePath.isBlank()) {
                log.warn("Telegram getFile returned no file_path [fileId={}]", fileId);
                return null;
            }

            // Step 2: download content
            String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;
            HttpRequest dlReq = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<byte[]> dlResp = httpClient.send(dlReq, HttpResponse.BodyHandlers.ofByteArray());
            if (dlResp.statusCode() < 200 || dlResp.statusCode() >= 300) {
                log.warn("Telegram file download failed [status={}, fileId={}]", dlResp.statusCode(), fileId);
                return null;
            }
            log.debug("EXIT downloadFile: [fileId={}, bytes={}]", fileId, dlResp.body().length);
            return dlResp.body();
        } catch (Exception e) {
            log.warn("Telegram downloadFile failed [fileId={}, error={}]", fileId, e.getMessage());
            return null;
        }
    }

    /**
     * Acknowledges a callback query (clears the loading spinner on the button).
     *
     * @param notificationText optional brief popup text shown to the user (max 200 chars)
     */
    public void answerCallbackQuery(String botToken, String callbackQueryId, String notificationText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callback_query_id", callbackQueryId);
        if (notificationText != null && !notificationText.isBlank()) {
            payload.put("text", truncate(notificationText, MAX_CB_TEXT));
        }
        post(botToken, "answerCallbackQuery", payload);
    }

    /**
     * Sends a binary file as a Telegram document.
     */
    public void sendDocument(String botToken,
                             String chatId,
                             String fileName,
                             String mimeType,
                             byte[] bytes,
                             String caption) {
        if (bytes == null || bytes.length == 0) {
            log.warn("sendDocument skipped empty payload [chatId={}, file={}]", chatId, fileName);
            return;
        }
        String boundary = "----vork-telegram-" + UUID.randomUUID();
        try {
            byte[] body = buildMultipartDocumentPayload(boundary, chatId, fileName, mimeType, bytes, caption);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + botToken + "/sendDocument"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Telegram sendDocument returned HTTP {} [body={}]",
                        resp.statusCode(), truncate(resp.body(), 300));
            }
        } catch (Exception e) {
            log.warn("Telegram sendDocument failed [chatId={}, file={}, error={}]",
                    chatId, fileName, e.getMessage());
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void post(String botToken, String method, Object payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + botToken + "/" + method))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Telegram {} returned HTTP {} [body={}]", method, resp.statusCode(),
                        truncate(resp.body(), 300));
            }
        } catch (Exception e) {
            log.warn("Telegram {} call failed: {}", method, e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static byte[] buildMultipartDocumentPayload(String boundary,
                                                        String chatId,
                                                        String fileName,
                                                        String mimeType,
                                                        byte[] bytes,
                                                        String caption) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFormPart(out, boundary, "chat_id", chatId);
        if (caption != null && !caption.isBlank()) {
            writeFormPart(out, boundary, "caption", truncate(caption, 1000));
        }

        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"document\"; filename=\""
                + escapeHeaderValue(fileName) + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + (mimeType == null || mimeType.isBlank()
                ? "application/octet-stream" : mimeType) + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
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
