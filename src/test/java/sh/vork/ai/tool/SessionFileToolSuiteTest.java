package sh.vork.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.function.CreateFolderRequest;
import sh.vork.ai.function.CreatePdfRequest;
import sh.vork.ai.function.DownloadFolderAsZipRequest;
import sh.vork.ai.function.ReadFileRequest;
import sh.vork.ai.function.WriteFileRequest;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.FileNode;
import sh.vork.filesystem.SessionFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionFileToolSuiteTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
        ToolExecutionContext.complete("session-abc");
        ToolExecutionContext.clear();
    }

    @Test
    void writeFileReturnsDownloadUrlForSessionArea() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        when(fs.writeText(eq(FileArea.SESSION), eq("session-abc"), eq("notes/todo.md"), eq("# TODO")))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "notes/todo.md",
                        6,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=notes%2Ftodo.md"));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(fs, new ObjectMapper());

        String response = tool.writeFile(new WriteFileRequest("notes/todo.md", "# TODO", "SESSION", null));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"name\":\"todo.md\""));
        assertTrue(response.contains("\"downloadUrl\":\"/api/session-files/download"));
    }

    @Test
    void readFileReturnsUtf8ContentForTextFiles() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("notes/readme.md")))
                .thenReturn(new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(fs, new ObjectMapper());

        String response = tool.readFile(new ReadFileRequest("notes/readme.md", "SESSION", 1024));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"content\":\"hello world\""));
        assertTrue(response.contains("\"downloadUrl\":\"/api/session-files/download?area=SESSION"));
    }

    @Test
    void createFolderCreatesTargetDirectory() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(fs, new ObjectMapper());

        String response = tool.createFolder(new CreateFolderRequest("docs/releases", "SESSION"));

        verify(fs).createDirectory(FileArea.SESSION, "session-abc", "docs/releases");
        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"path\":\"docs/releases\""));
    }

    @Test
    void downloadFolderAsZipWritesArchiveAndReturnsLink() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");

        when(fs.list(eq(FileArea.SESSION), eq("session-abc"), eq("docs")))
                .thenReturn(List.of(
                        new FileNode("a.md", "docs/a.md", false, 3, 1L),
                        new FileNode("sub", "docs/sub", true, 0, 1L)));
        when(fs.list(eq(FileArea.SESSION), eq("session-abc"), eq("docs/sub")))
                .thenReturn(List.of(new FileNode("b.txt", "docs/sub/b.txt", false, 3, 1L)));

        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("docs/a.md")))
                .thenReturn(new ByteArrayInputStream("AAA".getBytes(StandardCharsets.UTF_8)));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("docs/sub/b.txt")))
                .thenReturn(new ByteArrayInputStream("BBB".getBytes(StandardCharsets.UTF_8)));

        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("exports/docs.zip"), any(InputStream.class), anyLong()))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "exports/docs.zip",
                        123,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=exports%2Fdocs.zip"));

        SessionFileToolSuite tool = new SessionFileToolSuite(fs, new ObjectMapper());
        String response = tool.downloadFolderAsZip(
            new DownloadFolderAsZipRequest("docs", "exports/docs.zip", "SESSION", null, null));

        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(fs).write(eq(FileArea.SESSION), eq("session-abc"), eq("exports/docs.zip"), streamCaptor.capture(), anyLong());

        byte[] zipBytes = streamCaptor.getValue().readAllBytes();
        assertZipContains(zipBytes, "docs/a.md", "AAA");
        assertZipContains(zipBytes, "docs/sub/b.txt", "BBB");

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"downloadUrl\":\"/api/session-files/download"));
    }

    @Test
    void createPdfFromMarkdownWritesPdfAndReturnsDownloadUrl() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");

        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("reports/summary.pdf"), any(InputStream.class), anyLong()))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "reports/summary.pdf",
                        256,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=reports%2Fsummary.pdf"));

        SessionFileToolSuite tool = new SessionFileToolSuite(fs, new ObjectMapper());
        String response = tool.createPdf(new CreatePdfRequest("# Summary\n\n- one\n- two", "MARKDOWN", "reports/summary.pdf", "SESSION", null));

        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(fs).write(eq(FileArea.SESSION), eq("session-abc"), eq("reports/summary.pdf"), streamCaptor.capture(), anyLong());

        byte[] pdfBytes = streamCaptor.getValue().readAllBytes();
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 20);
        String header = new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII);
        assertEquals("%PDF-", header);

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"name\":\"summary.pdf\""));
        assertTrue(response.contains("\"downloadUrl\":\"/api/session-files/download"));
    }

        @SuppressWarnings("unchecked")
        @Test
        void downloadFolderAsZipDefaultPolicyAttachesOnlyZip() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");

        when(fs.writeText(eq(FileArea.SESSION), eq("session-abc"), eq("tmp/a.txt"), eq("A")))
            .thenReturn(new FileDescriptor(
                FileArea.SESSION,
                "session-abc",
                "tmp/a.txt",
                1,
                "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=tmp%2Fa.txt"));

        when(fs.list(eq(FileArea.SESSION), eq("session-abc"), eq("tmp")))
            .thenReturn(List.of(new FileNode("a.txt", "tmp/a.txt", false, 1, 1L)));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("tmp/a.txt")))
            .thenReturn(new ByteArrayInputStream("A".getBytes(StandardCharsets.UTF_8)));
        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("tmp.zip"), any(InputStream.class), anyLong()))
            .thenReturn(new FileDescriptor(
                FileArea.SESSION,
                "session-abc",
                "tmp.zip",
                100,
                "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=tmp.zip"));

        SessionFileToolSuite tool = new SessionFileToolSuite(fs, new ObjectMapper());
        tool.writeFile(new WriteFileRequest("tmp/a.txt", "A", "SESSION", true));
        tool.downloadFolderAsZip(new DownloadFolderAsZipRequest("tmp", "tmp.zip", "SESSION", true, null));

        Object raw = ToolExecutionContext.get("generated.session.attachments");
        assertTrue(raw instanceof List<?>);
        List<Map<String, String>> attachments = (List<Map<String, String>>) raw;
        assertEquals(1, attachments.size());
        assertEquals("tmp.zip", attachments.get(0).get("path"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void downloadFolderAsZipDefaultPolicyPreservesUnrelatedGeneratedAttachments() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");

        when(fs.writeText(eq(FileArea.SESSION), eq("session-abc"), eq("tmp/a.txt"), eq("A")))
            .thenReturn(new FileDescriptor(
                FileArea.SESSION,
                "session-abc",
                "tmp/a.txt",
                1,
                "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=tmp%2Fa.txt"));
        when(fs.writeText(eq(FileArea.SESSION), eq("session-abc"), eq("notes/keep.txt"), eq("KEEP")))
            .thenReturn(new FileDescriptor(
                FileArea.SESSION,
                "session-abc",
                "notes/keep.txt",
                4,
                "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=notes%2Fkeep.txt"));

        when(fs.list(eq(FileArea.SESSION), eq("session-abc"), eq("tmp")))
            .thenReturn(List.of(new FileNode("a.txt", "tmp/a.txt", false, 1, 1L)));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("tmp/a.txt")))
            .thenReturn(new ByteArrayInputStream("A".getBytes(StandardCharsets.UTF_8)));
        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("tmp.zip"), any(InputStream.class), anyLong()))
            .thenReturn(new FileDescriptor(
                FileArea.SESSION,
                "session-abc",
                "tmp.zip",
                100,
                "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=tmp.zip"));

        SessionFileToolSuite tool = new SessionFileToolSuite(fs, new ObjectMapper());
        tool.writeFile(new WriteFileRequest("tmp/a.txt", "A", "SESSION", true));
        tool.writeFile(new WriteFileRequest("notes/keep.txt", "KEEP", "SESSION", true));
        tool.downloadFolderAsZip(new DownloadFolderAsZipRequest("tmp", "tmp.zip", "SESSION", true, null));

        Object raw = ToolExecutionContext.get("generated.session.attachments");
        assertTrue(raw instanceof List<?>);
        List<Map<String, String>> attachments = (List<Map<String, String>>) raw;
        assertEquals(2, attachments.size());

        List<String> paths = attachments.stream().map(a -> a.get("path")).toList();
        assertTrue(paths.contains("notes/keep.txt"));
        assertTrue(paths.contains("tmp.zip"));
        }

    private static void assertZipContains(byte[] zipBytes, String expectedEntry, String expectedText) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (expectedEntry.equals(entry.getName())) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    zis.transferTo(baos);
                    assertEquals(expectedText, baos.toString(StandardCharsets.UTF_8));
                    return;
                }
            }
        }
        throw new AssertionError("Missing zip entry: " + expectedEntry);
    }
}
