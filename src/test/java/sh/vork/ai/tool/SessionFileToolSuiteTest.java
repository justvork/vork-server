package sh.vork.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.memory.InMemorySessionEnvironmentService;
import sh.vork.ai.function.FileExistsRequest;
import sh.vork.ai.function.FolderExistsRequest;
import sh.vork.ai.function.InstallCommandRequest;
import sh.vork.ai.function.IsCommandInstalledRequest;
import sh.vork.ai.process.SessionPathResolver;
import sh.vork.ai.function.CreateFolderRequest;
import sh.vork.ai.function.CreatePdfRequest;
import sh.vork.ai.function.DownloadFolderAsZipRequest;
import sh.vork.ai.function.ReadFileRequest;
import sh.vork.ai.function.ResolveArchitectureRequest;
import sh.vork.ai.function.WriteFileRequest;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.FileNode;
import sh.vork.filesystem.SessionFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @TempDir
    Path tempDir;

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
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());

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
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());

        String response = tool.readFile(new ReadFileRequest("notes/readme.md", "SESSION", 1024));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"content\":\"hello world\""));
        assertTrue(response.contains("\"downloadUrl\":\"/api/session-files/download?area=SESSION"));
    }

    @Test
    void createFolderCreatesTargetDirectory() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());

        String response = tool.createFolder(new CreateFolderRequest("docs/releases", "SESSION"));

        verify(fs).createDirectory(FileArea.SESSION, "session-abc", "docs/releases");
        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"path\":\"docs/releases\""));
    }

        @Test
        void fileAndFolderExistsReturnExpectedBooleans() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");

        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("tools/node/bin/node")))
            .thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(fs.list(eq(FileArea.SESSION), eq("session-abc"), eq("tools/node/bin")))
            .thenReturn(List.of());

        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());

        String fileExists = tool.fileExists(new FileExistsRequest("tools/node/bin/node", "SESSION"));
        String folderExists = tool.folderExists(new FolderExistsRequest("tools/node/bin", "SESSION"));
        String fileMissing = tool.fileExists(new FileExistsRequest("tools/node/bin/missing", "SESSION"));

        assertTrue(fileExists.contains("\"exists\":true"));
        assertTrue(folderExists.contains("\"exists\":true"));
        assertTrue(fileMissing.contains("\"exists\":false"));
        }

        @Test
        void installCommandRegistersValidatedBinPathIntoSessionEnv() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        InMemorySessionEnvironmentService env = new InMemorySessionEnvironmentService();

        Path binDir = tempDir.resolve("sessions").resolve("session-abc").resolve("tools").resolve("node").resolve("bin");
        Files.createDirectories(binDir);
        Files.writeString(binDir.resolve("node"), "#!/bin/sh\necho node\n", StandardCharsets.UTF_8);

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            env,
            new SessionPathResolver(tempDir.toString()),
            new ObjectMapper());

        String response = tool.installCommand(new InstallCommandRequest("tools/node/bin", "node", "SESSION"));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(env.getEnv("session-abc").containsKey("VORK_COMMAND_PATHS"));
        assertTrue(env.getEnv("session-abc").get("VORK_COMMAND_PATHS").contains("tools"));
        }

    @Test
    void installCommandRejectsMissingCommandInBinPath() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        InMemorySessionEnvironmentService env = new InMemorySessionEnvironmentService();

        Path binDir = tempDir.resolve("sessions").resolve("session-abc").resolve("tools").resolve("node").resolve("bin");
        Files.createDirectories(binDir);

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                env,
                new SessionPathResolver(tempDir.toString()),
                new ObjectMapper());

        String response = tool.installCommand(new InstallCommandRequest("tools/node/bin", "missing-command", "SESSION"));

        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("command was not found"));
    }

    @Test
    void isCommandInstalledFindsRegisteredCommand() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        InMemorySessionEnvironmentService env = new InMemorySessionEnvironmentService();

        Path binDir = tempDir.resolve("sessions").resolve("session-abc").resolve("tools").resolve("node").resolve("bin");
        Files.createDirectories(binDir);
        Files.writeString(binDir.resolve("node"), "#!/bin/sh\necho node\n", StandardCharsets.UTF_8);

        env.setEnv("session-abc", "VORK_COMMAND_PATHS", binDir.toString());

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                env,
                new SessionPathResolver(tempDir.toString()),
                new ObjectMapper());

        String response = tool.isCommandInstalled(new IsCommandInstalledRequest("node"));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"installed\":true"));
        assertTrue(response.contains("\"matchedPath\""));
    }

    @Test
        void resolveArchitectureReturnsKnownOrUnknownArchitecture() {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");

        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                new InMemorySessionEnvironmentService(),
                new SessionPathResolver(tempDir.toString()),
                new ObjectMapper());

        String response = tool.resolveArchitecture(new ResolveArchitectureRequest());

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"detectedArchitecture\""));
        assertTrue(response.contains("\"targetArchitecture\""));
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

        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());
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

        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());
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

        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());
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

        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            new ObjectMapper());
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
