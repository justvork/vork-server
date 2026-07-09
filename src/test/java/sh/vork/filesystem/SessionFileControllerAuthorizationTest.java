package sh.vork.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.DatabaseRepository;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionFileControllerAuthorizationTest {

    private SessionFileSystem sessionFileSystem;
    private DatabaseRepository<AiSession> sessionRepo;
    private MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionFileSystem = mock(SessionFileSystem.class);
        sessionRepo = mock(DatabaseRepository.class);
        SessionFileController controller = new SessionFileController(sessionFileSystem, sessionRepo);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listRejectsNonOwnerForSessionArea() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));

        mvc.perform(get("/api/session-files/list")
                        .param("area", "SESSION")
                        .param("sessionUuid", "s-1")
                        .principal(principal("bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void listAllowsOwnerForSessionArea() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));
        when(sessionFileSystem.list(eq(FileArea.SESSION), eq("s-1"), isNull()))
                .thenReturn(List.of(new FileNode("a.txt", "a.txt", false, 5L, 1L)));

        mvc.perform(get("/api/session-files/list")
                        .param("area", "SESSION")
                        .param("sessionUuid", "s-1")
                        .principal(principal("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.items[0].name").value("a.txt"));
    }

    @Test
    void uploadRejectsNonOwnerForSessionArea() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/session-files/upload")
                        .file(file)
                        .param("area", "SESSION")
                        .param("sessionUuid", "s-1")
                        .param("path", "notes.txt")
                        .principal(principal("bob")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void uploadAllowsOwnerForSessionArea() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));
        when(sessionFileSystem.write(any(), any(), any(), any(), anyLong()))
                .thenReturn(new FileDescriptor(FileArea.SESSION, "s-1", "notes.txt", 5L,
                        "/api/session-files/download?area=SESSION&sessionUuid=s-1&path=notes.txt"));

        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/session-files/upload")
                        .file(file)
                        .param("area", "SESSION")
                        .param("sessionUuid", "s-1")
                        .param("path", "notes.txt")
                        .principal(principal("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.downloadUrl").exists());
    }

    @Test
    void downloadRejectsNonOwnerForSessionArea() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));

        mvc.perform(get("/api/session-files/download")
                        .param("area", "SESSION")
                        .param("sessionUuid", "s-1")
                        .param("path", "notes.txt")
                        .principal(principal("bob")))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadAllowsOwnerForSessionArea() throws Exception {
        when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));
        when(sessionFileSystem.read(eq(FileArea.SESSION), eq("s-1"), eq("notes.txt")))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/session-files/download")
                        .param("area", "SESSION")
                        .param("sessionUuid", "s-1")
                        .param("path", "notes.txt")
                        .principal(principal("alice")))
                .andExpect(status().isOk())
                .andExpect(content().string("hello"));
    }

            @Test
            void downloadServesJpgInlineForOwner() throws Exception {
            when(sessionRepo.get("s-1")).thenReturn(session("s-1", "alice"));
            when(sessionFileSystem.read(eq(FileArea.SESSION), eq("s-1"), eq("image.jpg")))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

            mvc.perform(get("/api/session-files/download")
                    .param("area", "SESSION")
                    .param("sessionUuid", "s-1")
                    .param("path", "image.jpg")
                    .principal(principal("alice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline;")))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("image/jpeg")));
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
