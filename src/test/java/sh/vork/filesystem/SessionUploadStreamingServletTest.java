package sh.vork.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.DatabaseRepository;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionUploadStreamingServletTest {

    private SessionFileSystem sessionFileSystem;
    private DatabaseRepository<AiSession> sessionRepo;
    private SessionUploadStreamingServlet servlet;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionFileSystem = mock(SessionFileSystem.class);
        sessionRepo = mock(DatabaseRepository.class);
        SessionFileAuthorizationService authz = new SessionFileAuthorizationService(sessionRepo);
        servlet = new SessionUploadStreamingServlet(sessionFileSystem, authz, new ObjectMapper());
    }

    @Test
    void uploadStreamRejectsNonOwnerForSessionArea() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/session-files/upload-stream");
        request.setParameter("area", "SESSION");
        request.setParameter("sessionUuid", "s-1");
        request.setParameter("path", "notes.txt");
        request.setContentType("text/plain");
        request.setContent("hello".getBytes(StandardCharsets.UTF_8));
        request.setUserPrincipal(principal("bob"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"status\":\"error\""));
    }

    @Test
    void uploadStreamAllowsOwnerAndWritesRequestBody() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));
        when(sessionFileSystem.write(any(), any(), any(), any(), anyLong()))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "s-1",
                        "logs/big.log",
                        5L,
                        "/api/session-files/download?area=SESSION&sessionUuid=s-1&path=logs%2Fbig.log"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/session-files/upload-stream");
        request.setParameter("area", "SESSION");
        request.setParameter("sessionUuid", "s-1");
        request.setParameter("path", "logs/big.log");
        request.setContentType("text/plain");
        request.setContent("hello".getBytes(StandardCharsets.UTF_8));
        request.setUserPrincipal(principal("alice"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"status\":\"ok\""));
        assertTrue(response.getContentAsString().contains("\"downloadUrl\""));
        verify(sessionFileSystem).write(eq(FileArea.SESSION), eq("s-1"), eq("logs/big.log"), any(InputStream.class), eq(5L));
    }

    @Test
    void uploadStreamRejectsEmptyBody() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/session-files/upload-stream");
        request.setParameter("area", "SESSION");
        request.setParameter("sessionUuid", "s-1");
        request.setParameter("path", "empty.bin");
        request.setContentType("application/octet-stream");
        request.setContent(new byte[0]);
        request.setUserPrincipal(principal("alice"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("Uploaded file is empty"));
    }

    private static Principal principal(String username) {
        return () -> username;
    }

    private static AiSession session(String uuid, String username) {
        return new AiSession(
                uuid,
                "GEMINI",
                SessionOriginMode.WEB,
                username,
                "test",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
