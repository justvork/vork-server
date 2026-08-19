package sh.vork.notification.sendgrid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.notification.Notification;

class SendGridNotificationProviderTest {

    @Test
    void buildRequestBodyUsesHtmlContentTypeWhenRequested() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SendGridNotificationProvider provider = new SendGridNotificationProvider(mapper);

        Notification notification = Notification.of(
                List.of("to@example.com"),
                "Subject",
                "<h1>Hello</h1>",
                Notification.CONTENT_TYPE_HTML);

        Method buildRequestBody = SendGridNotificationProvider.class.getDeclaredMethod(
                "buildRequestBody", Notification.class, String.class, String.class);
        buildRequestBody.setAccessible(true);

        String requestBody = (String) buildRequestBody.invoke(provider, notification, "from@example.com", "Vork");
        JsonNode root = mapper.readTree(requestBody);

        assertEquals("text/html", root.path("content").get(0).path("type").asText());
    }

    @Test
    void buildRequestBodyUsesPlainTextByDefault() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SendGridNotificationProvider provider = new SendGridNotificationProvider(mapper);

        Notification notification = Notification.of(
                List.of("to@example.com"),
                "Subject",
                "Hello");

        Method buildRequestBody = SendGridNotificationProvider.class.getDeclaredMethod(
                "buildRequestBody", Notification.class, String.class, String.class);
        buildRequestBody.setAccessible(true);

        String requestBody = (String) buildRequestBody.invoke(provider, notification, "from@example.com", "Vork");
        JsonNode root = mapper.readTree(requestBody);

        assertEquals("text/plain", root.path("content").get(0).path("type").asText());
    }
}
