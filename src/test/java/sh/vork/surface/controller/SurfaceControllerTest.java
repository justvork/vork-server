package sh.vork.surface.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.surface.service.SurfaceService;

/**
 * Unit tests for {@link SurfaceController}.
 */
class SurfaceControllerTest {

    @Test
    void getSurfaceSession_returnsMessagesInResponse() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceController controller = new SurfaceController(surfaceService, sessionFileSystem);

        List<AiChatMessage> messages = List.of(
                new AiChatMessage("msg-1", "USER", "Hello", 1L, null),
                new AiChatMessage("msg-2", "ASSISTANT", "Hi there", 2L, null));

        AiSession session = sessionWithMessages(messages);

        Principal principal = () -> "admin";
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);

        ResponseEntity<?> response = controller.getSurfaceSession("surface-1", principal);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("ok", body.get("status"));
        assertEquals("session-1", body.get("sessionUuid"));
        assertEquals("agent-1", body.get("activeAgentTemplateId"));
        assertEquals("Test Surface Session", body.get("name"));
        assertNotNull(body.get("messages"));
        assertEquals(messages, body.get("messages"));
    }

    @Test
    void previewSurfaceFile_returnsSessionFileWithMimeType() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceController controller = new SurfaceController(surfaceService, sessionFileSystem);

        AiSession session = sessionWithMessages(List.of());
        Principal principal = () -> "admin";

        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "index.html"))
                .thenReturn(new ByteArrayInputStream("<html>Hello</html>".getBytes()));

        ResponseEntity<?> response = controller.previewSurfaceFile("surface-1", "index.html", principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/html", response.getHeaders().getContentType().toString());
        byte[] body = assertInstanceOf(byte[].class, response.getBody());
        assertEquals("<html>Hello</html>", new String(body));
        verify(sessionFileSystem).read(FileArea.SESSION, "session-1", "index.html");
    }

    @Test
    void previewSurfaceFile_returnsNotFoundWhenFileMissing() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceController controller = new SurfaceController(surfaceService, sessionFileSystem);

        AiSession session = sessionWithMessages(List.of());
        Principal principal = () -> "admin";

        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "missing.html"))
                .thenThrow(new java.io.FileNotFoundException("missing"));

        ResponseEntity<?> response = controller.previewSurfaceFile("surface-1", "missing.html", principal);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Preview file not found", response.getBody());
    }

    private static AiSession sessionWithMessages(List<AiChatMessage> messages) {
        return new AiSession(
                "session-1",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Test Surface Session",
                1L,
                0,
                messages,
                null,
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
