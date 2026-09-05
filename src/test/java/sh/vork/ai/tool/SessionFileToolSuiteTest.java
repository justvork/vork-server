package sh.vork.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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
import sh.vork.ai.function.ExtractTarRequest;
import sh.vork.ai.function.GetTextFileInfoRequest;
import sh.vork.ai.function.ReadTextFileRangeRequest;
import sh.vork.ai.function.ReadFileRequest;
import sh.vork.ai.function.ResolveArchitectureRequest;
import sh.vork.ai.function.SearchTextFileRequest;
import sh.vork.ai.function.WriteBase64FileRequest;
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
import java.util.ArrayList;
import java.util.Base64;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionFileToolSuiteTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void writeBase64FileDecodesAndWritesBinaryBytes() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        byte[] expected = "PNG".getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(expected);
        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("attachments/img.bin"), any(InputStream.class), eq((long) expected.length)))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "attachments/img.bin",
                        expected.length,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=attachments%2Fimg.bin"));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                new InMemorySessionEnvironmentService(),
                new SessionPathResolver(),
                new ObjectMapper());

        String response = tool.writeBase64File(new WriteBase64FileRequest("attachments/img.bin", base64, "SESSION", null));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"name\":\"img.bin\""));

        ArgumentCaptor<InputStream> contentCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(fs).write(eq(FileArea.SESSION), eq("session-abc"), eq("attachments/img.bin"), contentCaptor.capture(), eq((long) expected.length));
        byte[] actual = contentCaptor.getValue().readAllBytes();
        assertEquals("PNG", new String(actual, StandardCharsets.UTF_8));
    }

    @Test
    void writeBase64FileDecodesUrlSafeBase64WithoutModeSwitch() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        byte[] expected = new byte[] {(byte) 0xfb, (byte) 0xef, (byte) 0xff};
        String base64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(expected);
        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("attachments/data.bin"), any(InputStream.class), eq((long) expected.length)))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "attachments/data.bin",
                        expected.length,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=attachments%2Fdata.bin"));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                new InMemorySessionEnvironmentService(),
                new SessionPathResolver(),
                new ObjectMapper());

        String response = tool.writeBase64File(new WriteBase64FileRequest("attachments/data.bin", base64Url, "SESSION", null));

        assertTrue(response.contains("\"status\":\"ok\""));
        ArgumentCaptor<InputStream> contentCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(fs).write(eq(FileArea.SESSION), eq("session-abc"), eq("attachments/data.bin"), contentCaptor.capture(), eq((long) expected.length));
        byte[] actual = contentCaptor.getValue().readAllBytes();
        assertEquals(Base64.getEncoder().encodeToString(expected), Base64.getEncoder().encodeToString(actual));
    }

    @Test
    void writeBase64FileRejectsInvalidBase64Payload() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                new InMemorySessionEnvironmentService(),
                new SessionPathResolver(),
                new ObjectMapper());

        String response = tool.writeBase64File(new WriteBase64FileRequest("attachments/img.bin", "%%%", "SESSION", null));

        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("base64Content is not valid Base64"));
        verify(fs, never()).write(eq(FileArea.SESSION), eq("session-abc"), eq("attachments/img.bin"), any(InputStream.class), anyLong());
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

    @Test
    void extractTarWritesEntriesToDestination() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        byte[] tarBytes = createTar(Map.of(
                "nested/a.log", "AAA",
                "nested/b.log", "BBB"
        ));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("archives/logs.tar")))
                .thenReturn(new ByteArrayInputStream(tarBytes));

        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("out/nested/a.log"), any(InputStream.class), eq(3L)))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "out/nested/a.log",
                        3,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=out%2Fnested%2Fa.log"));
        when(fs.write(eq(FileArea.SESSION), eq("session-abc"), eq("out/nested/b.log"), any(InputStream.class), eq(3L)))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "session-abc",
                        "out/nested/b.log",
                        3,
                        "/api/session-files/download?area=SESSION&sessionUuid=session-abc&path=out%2Fnested%2Fb.log"));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                new InMemorySessionEnvironmentService(),
                new SessionPathResolver(),
                objectMapper);

        String response = tool.extractTar(new ExtractTarRequest("archives/logs.tar", "out", "SESSION", true));

        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"filesExtracted\":2"));
        verify(fs).write(eq(FileArea.SESSION), eq("session-abc"), eq("out/nested/a.log"), any(InputStream.class), eq(3L));
        verify(fs).write(eq(FileArea.SESSION), eq("session-abc"), eq("out/nested/b.log"), any(InputStream.class), eq(3L));
    }

    @Test
    void extractTarRejectsTraversalEntries() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        byte[] tarBytes = createTar(Map.of("../escape.txt", "bad"));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("archives/bad.tar")))
                .thenReturn(new ByteArrayInputStream(tarBytes));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
                fs,
                new InMemorySessionEnvironmentService(),
                new SessionPathResolver(),
                objectMapper);

        String response = tool.extractTar(new ExtractTarRequest("archives/bad.tar", "out", "SESSION", false));

        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("Invalid archive entry path"));
        verify(fs, never()).write(eq(FileArea.SESSION), eq("session-abc"), any(), any(InputStream.class), anyLong());
    }

    private static byte[] createTar(Map<String, String> entries) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(baos, StandardCharsets.UTF_8.name())) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String name = entry.getKey();
                String content = entry.getValue();
                TarArchiveEntry tarEntry = new TarArchiveEntry(name);
                if (content == null) {
                    tarOut.putArchiveEntry(tarEntry);
                    tarOut.closeArchiveEntry();
                    continue;
                }
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                tarEntry.setSize(bytes.length);
                tarOut.putArchiveEntry(tarEntry);
                tarOut.write(bytes);
                tarOut.closeArchiveEntry();
            }
            tarOut.finish();
            return baos.toByteArray();
        }
    }

        @Test
        void getTextFileInfoReturnsSizeAndLineCount() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = "alpha\nbeta\n";
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/app.log")))
            .thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        String response = tool.getTextFileInfo(new GetTextFileInfoRequest("logs/app.log", "SESSION"));
        JsonNode root = objectMapper.readTree(response);

        assertEquals("ok", root.get("status").asText());
        assertEquals("logs/app.log", root.get("path").asText());
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, root.get("sizeBytes").asLong());
        assertEquals(2, root.get("lineCount").asLong());
        assertEquals("UTF-8", root.get("encoding").asText());
        }

        @Test
        void searchTextFileSupportsContainsExactAndRegex() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = String.join("\n", List.of(
            "INFO start",
            "ERROR Disk full",
            "warn lower",
            "ID=42",
            "ERROR Network"
        ));

        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/app.log")))
            .thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode contains = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "error", "CONTAINS", false, 0, 0, 10, null, null, "SESSION")));
        JsonNode exact = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "ERROR Disk full", "EXACT", true, 0, 0, 10, null, null, "SESSION")));
        JsonNode regex = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "ID=\\d+", "REGEX", true, 0, 0, 10, null, null, "SESSION")));

        assertEquals(2, contains.get("returnedMatches").asInt());
        assertEquals(1, exact.get("returnedMatches").asInt());
        assertEquals(1, regex.get("returnedMatches").asInt());
        }

        @Test
        void searchTextFileHandlesCaseSensitivity() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = "ERROR one\nerror two\n";
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/app.log")))
            .thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode insensitive = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "error", "CONTAINS", false, 0, 0, 10, null, null, "SESSION")));
        JsonNode sensitive = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "error", "CONTAINS", true, 0, 0, 10, null, null, "SESSION")));

        assertEquals(2, insensitive.get("returnedMatches").asInt());
        assertEquals(1, sensitive.get("returnedMatches").asInt());
        }

        @Test
        void searchTextFileMergesOverlappingContextBlocks() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = String.join("\n", List.of(
            "l1",
            "l2",
            "ERROR a",
            "l4",
            "ERROR b",
            "l6"
        ));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/app.log")))
            .thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode root = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "ERROR", "CONTAINS", true, 1, 1, 10, null, null, "SESSION")));
        JsonNode blocks = root.get("blocks");

        assertEquals(1, blocks.size());
        assertEquals(2, blocks.get(0).get("startLine").asLong());
        assertEquals(6, blocks.get(0).get("endLine").asLong());
        assertEquals(2, blocks.get(0).get("matchLines").size());
        }

        @Test
        void searchTextFileHonoursMaxMatchesWithExplicitCappedSemantics() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 5000; i++) {
            lines.add("ERROR line " + i);
        }
        String text = String.join("\n", lines);
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/huge.log")))
            .thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode root = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/huge.log", "ERROR", "CONTAINS", true, 0, 0, 3, null, null, "SESSION")));

        assertEquals(3, root.get("returnedMatches").asInt());
        assertTrue(root.get("truncated").asBoolean());
        assertTrue(root.get("moreMatchesPossible").asBoolean());
        assertTrue(root.get("totalMatches").isNull());
        }

        @Test
        void searchTextFileHonoursStartAndEndLineBounds() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = String.join("\n", List.of(
            "ERROR before",
            "ok",
            "ERROR in",
            "ok",
            "ERROR after"
        ));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/app.log")))
            .thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode root = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "ERROR", "CONTAINS", true, 0, 0, 10, 2L, 4L, "SESSION")));

        assertEquals(1, root.get("returnedMatches").asInt());
        assertEquals(3, root.get("blocks").get(0).get("matchLines").get(0).asLong());
        }

        @Test
        void searchTextFileReturnsValidationErrorForInvalidRegex() {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        String response = tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "(", "REGEX", true, 0, 0, 10, null, null, "SESSION"));

        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("Invalid regex"));
        }

        @Test
        void searchTextFileHandlesEmptyFileAndNoMatches() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/empty.log")))
            .thenReturn(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0])
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode root = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/empty.log", "ERROR", "CONTAINS", true, 0, 0, 10, null, null, "SESSION")));

        assertEquals(0, root.get("returnedMatches").asInt());
        assertEquals(0, root.get("blocks").size());
        assertEquals(0, root.get("totalMatches").asInt());
        }

        @Test
        void searchTextFileHandlesNonEmptyFileWithNoMatches() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = "INFO startup\nINFO healthy\n";
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/app.log")))
            .thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode root = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/app.log", "ERROR", "CONTAINS", true, 0, 0, 10, null, null, "SESSION")));

        assertEquals(0, root.get("returnedMatches").asInt());
        assertEquals(0, root.get("blocks").size());
        assertEquals(0, root.get("totalMatches").asInt());
        assertTrue(root.get("allMatchesScanned").asBoolean());
        }

        @Test
        void searchTextFileFindsFirstAndLastLineMatches() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = "ERROR first\nmid\nERROR last\n";
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/edges.log")))
            .thenReturn(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
            );

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode root = objectMapper.readTree(tool.searchTextFile(new SearchTextFileRequest(
            "logs/edges.log", "ERROR", "CONTAINS", true, 0, 0, 10, null, null, "SESSION")));

        assertEquals(2, root.get("returnedMatches").asInt());
        assertEquals(1, root.get("blocks").get(0).get("matchLines").get(0).asLong());
        assertEquals(3, root.get("blocks").get(1).get("matchLines").get(0).asLong());
        }

        @Test
        void searchTextFileReturnsErrorForInvalidPathTraversalAttempt() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("../secrets.log")))
            .thenThrow(new IllegalArgumentException("Path traversal is not allowed"));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        String response = tool.searchTextFile(new SearchTextFileRequest(
            "../secrets.log", "ERROR", "CONTAINS", true, 0, 0, 10, null, null, "SESSION"));

        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("Path traversal"));
        }

        @Test
        void readTextFileRangeStreamsRequestedRegion() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String text = String.join("\n", List.of("l1", "l2", "l3", "l4", "l5"));
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/app.log")))
            .thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        JsonNode root = objectMapper.readTree(tool.readTextFileRange(new ReadTextFileRangeRequest(
            "logs/app.log", 2L, 4L, "SESSION")));

        assertEquals("l2\nl3\nl4", root.get("text").asText());
        assertEquals(3, root.get("linesRead").asInt());
        }

        @Test
        void readTextFileRangeValidatesLineRange() {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        String response = tool.readTextFileRange(new ReadTextFileRangeRequest("logs/app.log", 20L, 10L, "SESSION"));
        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("endLine must be >= startLine"));
        }

        @Test
        void readTextFileRangeEnforcesLineLimit() {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        String response = tool.readTextFileRange(new ReadTextFileRangeRequest("logs/app.log", 1L, 3000L, "SESSION"));
        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("exceeds limit"));
        }

        @Test
        void readTextFileRangeEnforcesByteLimit() throws Exception {
        SessionFileSystem fs = mock(SessionFileSystem.class);
        String giantLine = "x".repeat(400_000);
        when(fs.read(eq(FileArea.SESSION), eq("session-abc"), eq("logs/big.log")))
            .thenReturn(new ByteArrayInputStream((giantLine + "\n").getBytes(StandardCharsets.UTF_8)));

        ToolExecutionContext.bindSessionUuid("session-abc");
        SessionFileToolSuite tool = new SessionFileToolSuite(
            fs,
            new InMemorySessionEnvironmentService(),
            new SessionPathResolver(),
            objectMapper);

        String response = tool.readTextFileRange(new ReadTextFileRangeRequest("logs/big.log", 1L, 1L, "SESSION"));
        assertTrue(response.contains("\"status\":\"error\""));
        assertTrue(response.contains("max response size"));
        }
}
