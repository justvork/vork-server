package sh.vork.notification.smtp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpNotificationProviderTest {

    private static final class CapturingMailSender extends JavaMailSenderImpl {
        private MimeMessage captured;

        @Override
        public void send(MimeMessage mimeMessage) {
            this.captured = mimeMessage;
        }
    }

    @Test
    void sendOneMarksHtmlBodyWhenRequested() throws Exception {
        SmtpNotificationProvider provider = new SmtpNotificationProvider();
        CapturingMailSender sender = new CapturingMailSender();

        Method sendOne = SmtpNotificationProvider.class.getDeclaredMethod(
                "sendOne",
                JavaMailSenderImpl.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class);
        sendOne.setAccessible(true);

        sendOne.invoke(provider,
                sender,
                "from@example.com",
                "Vork",
                "to@example.com",
                "Subject",
                "<h1>Hello</h1>",
                true);

        assertNotNull(sender.captured);
        Object content = sender.captured.getContent();
        String body = content instanceof String s
            ? s
            : new String(((InputStream) content).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("<h1>Hello</h1>"));
    }

    @Test
    void sendOneKeepsPlainTextWhenHtmlNotRequested() throws Exception {
        SmtpNotificationProvider provider = new SmtpNotificationProvider();
        CapturingMailSender sender = new CapturingMailSender();

        Method sendOne = SmtpNotificationProvider.class.getDeclaredMethod(
                "sendOne",
                JavaMailSenderImpl.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class);
        sendOne.setAccessible(true);

        sendOne.invoke(provider,
                sender,
                "from@example.com",
                "Vork",
                "to@example.com",
                "Subject",
                "Hello",
                false);

        assertNotNull(sender.captured);
        Object content = sender.captured.getContent();
        String body = content instanceof String s
            ? s
            : new String(((InputStream) content).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("Hello"));
    }
}
