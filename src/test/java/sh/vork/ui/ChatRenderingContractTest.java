package sh.vork.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRenderingContractTest {

    @Test
    void chatRendererIncludesDistinctOutgoingRoleRendering() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/chat.js"));
        String css = Files.readString(Path.of("src/main/resources/static/css/chat.css"));

        assertTrue(js.contains("msg.role === 'OUTGOING'"));
        assertTrue(js.contains("outgoing-message-card"));
        assertTrue(js.contains("isUser || isOutgoing ? ' user' : ''"));
        assertTrue(css.contains(".bubble.outgoing"));
        assertTrue(css.contains(".avatar.outgoing"));
    }
}
