package sh.vork.ai.tool;

import com.sshtools.client.sftp.SftpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.function.DownloadFileRequest;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.ssh.VirtualSshService;

import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadFileToolTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
        ToolExecutionContext.complete("session-abc");
        ToolExecutionContext.clear();
    }

    @Test
    void downloadWithoutLocalPathStoresInSessionArea() throws Exception {
        VirtualSshService sshService = mock(VirtualSshService.class);
        SessionFileSystem fs = mock(SessionFileSystem.class);
        SftpClient sftp = mock(SftpClient.class);

        when(sshService.getSftpClient(eq("session-abc"), eq("node-a"))).thenReturn(sftp);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write("DATA".getBytes());
            return null;
        }).when(sftp).get(eq("/var/log/app.log"), any(OutputStream.class));

        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), any(), any(), eq(4L)))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "downloads/abc-app.log",
                        4,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=downloads%2Fabc-app.log"));

        DownloadFileTool tool = new DownloadFileTool(sshService, fs);
        MDC.put("sessionUuid", "session-abc");
        ToolExecutionContext.bindSessionUuid("session-abc");

        String result = tool.execute(new DownloadFileRequest("node-a", "/var/log/app.log", null));

        verify(fs).write(eq(FileArea.SESSION), eq("session-abc"), any(), any(), eq(4L));
        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"location\":\"session\""));
        assertTrue(result.contains("\"downloadUrl\":\"/api/session-files/download"));

        Object attachments = ToolExecutionContext.get("generated.session.attachments");
        assertTrue(attachments instanceof java.util.List<?>);
    }
}
