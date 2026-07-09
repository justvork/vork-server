package sh.vork.ai.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import sh.vork.ai.function.CreateSessionTextFileRequest;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.SessionFileSystem;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateSessionTextFileToolTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void returnsDownloadUrlAndNameForSessionArea() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        when(fs.writeText(eq(FileArea.SESSION), eq("session-abc"), eq("notes/summary.md"), eq("hello")))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "notes/summary.md",
                        5,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=notes%2Fsummary.md"));

        MDC.put("sessionUuid", "session-abc");
        CreateSessionTextFileTool tool = new CreateSessionTextFileTool(fs);

        String response = tool.execute(new CreateSessionTextFileRequest("notes/summary.md", "hello", "SESSION"));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"name\":\"summary.md\""));
        assertTrue(response.contains("\"downloadUrl\":\"/api/session-files/download"));
    }
}
