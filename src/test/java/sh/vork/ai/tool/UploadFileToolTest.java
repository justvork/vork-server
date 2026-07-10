package sh.vork.ai.tool;

import com.sshtools.client.sftp.SftpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import sh.vork.ai.function.UploadFileRequest;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.ssh.VirtualSshService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadFileToolTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void uploadsUsingSessionUrlReference() throws Exception {
        VirtualSshService sshService = mock(VirtualSshService.class);
        SessionFileSystem fs = mock(SessionFileSystem.class);
        SftpClient sftp = mock(SftpClient.class);

        when(sshService.getSftpClient(eq("session-abc"), eq("node-a"))).thenReturn(sftp);
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("docs/report.txt")))
                .thenReturn(new ByteArrayInputStream("hello".getBytes()));

        UploadFileTool tool = new UploadFileTool(sshService, fs);
        MDC.put("sessionUuid", "session-abc");

        String result = tool.execute(new UploadFileRequest(
                "node-a",
                "session-url:/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=docs%2Freport.txt",
                "/remote/report.txt"));

        verify(sftp).put(any(InputStream.class), eq("/remote/report.txt"), isNull());
        assertTrue(result.contains("\"status\":\"ok\""));
        assertTrue(result.contains("\"source\":\"session\""));
    }
}
