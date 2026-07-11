package sh.vork.ai.tool;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import sh.vork.ai.function.ExtractZipRequest;
import sh.vork.ai.function.FileExistsRequest;
import sh.vork.ai.function.FolderExistsRequest;
import sh.vork.ai.function.InstallCommandRequest;
import sh.vork.ai.function.IsCommandInstalledRequest;
import sh.vork.ai.function.CreatePdfRequest;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.function.CreateFolderRequest;
import sh.vork.ai.function.DownloadFolderAsZipRequest;
import sh.vork.ai.function.ListFilesRequest;
import sh.vork.ai.function.ReadFileRequest;
import sh.vork.ai.function.ResolveArchitectureRequest;
import sh.vork.ai.function.WriteFileRequest;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.process.ProcessExecutionConfigResolver;
import sh.vork.ai.process.SessionPathResolver;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.FileNode;
import sh.vork.filesystem.SessionFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Session-file tool suite for AI file operations.
 */
@Component
public class SessionFileToolSuite {

    private static final Logger log = LoggerFactory.getLogger(SessionFileToolSuite.class);
    private static final int DEFAULT_MAX_READ_BYTES = 200_000;
    private static final String GENERATED_ATTACHMENTS_CONTEXT_KEY = "generated.session.attachments";
    private static final Pattern PATH_SPLIT_PATTERN = Pattern.compile(Pattern.quote(java.io.File.pathSeparator));
        private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder().build();

    private final SessionFileSystem sessionFileSystem;
    private final SessionEnvironmentService sessionEnvironmentService;
    private final SessionPathResolver sessionPathResolver;
    private final ObjectMapper objectMapper;

    public SessionFileToolSuite(SessionFileSystem sessionFileSystem,
                                SessionEnvironmentService sessionEnvironmentService,
                                SessionPathResolver sessionPathResolver,
                                ObjectMapper objectMapper) {
        this.sessionFileSystem = sessionFileSystem;
        this.sessionEnvironmentService = sessionEnvironmentService;
        this.sessionPathResolver = sessionPathResolver;
        this.objectMapper = objectMapper;
    }

    public String writeFile(WriteFileRequest req) {
        log.debug("ENTER writeFile: area={}, path={}", req == null ? null : req.area(), req == null ? null : req.path());
        if (req == null || req.path() == null || req.path().isBlank()) {
            return error("path is required");
        }
        if (req.content() == null) {
            return error("content is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        try {
            FileDescriptor descriptor = sessionFileSystem.writeText(area, owner, req.path(), req.content());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", descriptor.area().name());
            payload.put("path", descriptor.path());
            payload.put("name", extractFileName(descriptor.path()));
            payload.put("sizeBytes", descriptor.sizeBytes());
            payload.put("downloadUrl", descriptor.downloadUrl());
            boolean attachToChat = req.attachToChat() == null || req.attachToChat();
            if (attachToChat) {
                recordGeneratedAttachment(descriptor.path(), inferMimeType(descriptor.path()), descriptor.downloadUrl());
            }
            log.debug("EXIT writeFile: area={}, session={}, path={}, size={}",
                    descriptor.area(), descriptor.sessionUuid(), descriptor.path(), descriptor.sizeBytes());
            return json(payload);
        } catch (Exception ex) {
            log.warn("writeFile failed [path={}]: {}", req.path(), ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String readFile(ReadFileRequest req) {
        log.debug("ENTER readFile: area={}, path={}, maxBytes={}",
                req == null ? null : req.area(),
                req == null ? null : req.path(),
                req == null ? null : req.maxBytes());
        if (req == null || req.path() == null || req.path().isBlank()) {
            return error("path is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;
        int maxBytes = req.maxBytes() == null ? DEFAULT_MAX_READ_BYTES : Math.max(1, req.maxBytes());

        try (InputStream in = sessionFileSystem.read(area, owner, req.path())) {
            byte[] read = in.readNBytes(maxBytes + 1);
            boolean truncated = read.length > maxBytes;
            byte[] payloadBytes = truncated ? java.util.Arrays.copyOf(read, maxBytes) : read;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", area.name());
            payload.put("path", req.path());
            payload.put("name", extractFileName(req.path()));
            payload.put("downloadUrl", buildDownloadUrl(area, owner, req.path()));
            payload.put("bytesRead", payloadBytes.length);
            payload.put("truncated", truncated);

            if (isTextFile(req.path())) {
                payload.put("encoding", "UTF-8");
                payload.put("content", new String(payloadBytes, StandardCharsets.UTF_8));
            } else {
                payload.put("contentBase64", Base64.getEncoder().encodeToString(payloadBytes));
                payload.put("contentType", inferMimeType(req.path()));
            }

            log.debug("EXIT readFile: area={}, path={}, bytesRead={}, truncated={}",
                    area, req.path(), payloadBytes.length, truncated);
            return json(payload);
        } catch (Exception ex) {
            log.warn("readFile failed [path={}]: {}", req.path(), ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String createFolder(CreateFolderRequest req) {
        log.debug("ENTER createFolder: area={}, path={}", req == null ? null : req.area(), req == null ? null : req.path());
        if (req == null || req.path() == null || req.path().isBlank()) {
            return error("path is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        try {
            sessionFileSystem.createDirectory(area, owner, req.path());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", area.name());
            payload.put("path", req.path());
            log.debug("EXIT createFolder: area={}, path={}", area, req.path());
            return json(payload);
        } catch (Exception ex) {
            log.warn("createFolder failed [path={}]: {}", req.path(), ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String listFiles(ListFilesRequest req) {
        log.debug("ENTER listFiles: area={}, path={}", req == null ? null : req.area(), req == null ? null : req.path());
        FileArea area = parseArea(req == null ? null : req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;
        String dir = (req == null || req.path() == null) ? "" : req.path();

        try {
            List<FileNode> nodes = sessionFileSystem.list(area, owner, dir);
            List<Map<String, Object>> items = new ArrayList<>();
            for (FileNode node : nodes) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", node.name());
                item.put("path", node.path());
                item.put("directory", node.directory());
                item.put("sizeBytes", node.sizeBytes());
                item.put("modifiedAt", node.modifiedAt());
                if (!node.directory()) {
                    item.put("downloadUrl", buildDownloadUrl(area, owner, node.path()));
                }
                items.add(item);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", area.name());
            payload.put("path", dir);
            payload.put("count", items.size());
            payload.put("items", items);
            log.debug("EXIT listFiles: area={}, path={}, count={}", area, dir, items.size());
            return json(payload);
        } catch (Exception ex) {
            log.warn("listFiles failed [path={}]: {}", dir, ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String downloadFolderAsZip(DownloadFolderAsZipRequest req) {
        log.debug("ENTER downloadFolderAsZip: area={}, folderPath={}, outputZipPath={}",
                req == null ? null : req.area(),
                req == null ? null : req.folderPath(),
                req == null ? null : req.outputZipPath());
        if (req == null || req.folderPath() == null || req.folderPath().isBlank()) {
            return error("folderPath is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        String folderPath = normalizePath(req.folderPath());
        String defaultZipName = extractFileName(folderPath) + ".zip";
        String outputZipPath = (req.outputZipPath() == null || req.outputZipPath().isBlank())
                ? defaultZipName
                : req.outputZipPath();

        try {
            List<String> zippedFilePaths = collectFilePaths(area, owner, folderPath);
            byte[] zipBytes = zipDirectory(area, owner, folderPath, zippedFilePaths);
            FileDescriptor descriptor = sessionFileSystem.write(
                    area,
                    owner,
                    outputZipPath,
                    new ByteArrayInputStream(zipBytes),
                    zipBytes.length);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", descriptor.area().name());
            payload.put("folderPath", folderPath);
            payload.put("zipPath", descriptor.path());
            payload.put("sizeBytes", descriptor.sizeBytes());
            payload.put("downloadUrl", descriptor.downloadUrl());
            boolean attachToChat = req.attachToChat() == null || req.attachToChat();
            boolean attachOnlyZip = req.attachOnlyZip() == null || req.attachOnlyZip();
            if (attachToChat) {
                if (attachOnlyZip) {
                    removeGeneratedAttachmentsForPaths(zippedFilePaths);
                }
                recordGeneratedAttachment(descriptor.path(), "application/zip", descriptor.downloadUrl());
            }
            log.debug("EXIT downloadFolderAsZip: area={}, folderPath={}, zipPath={}, size={}",
                    area, folderPath, descriptor.path(), descriptor.sizeBytes());
            return json(payload);
        } catch (Exception ex) {
            log.warn("downloadFolderAsZip failed [folderPath={}]: {}", folderPath, ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String createPdf(CreatePdfRequest req) {
        log.debug("ENTER createPdf: area={}, format={}, outputPath={}",
                req == null ? null : req.area(),
                req == null ? null : req.format(),
                req == null ? null : req.outputPath());
        if (req == null || req.content() == null || req.content().isBlank()) {
            return error("content is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        String outputPath = (req.outputPath() == null || req.outputPath().isBlank())
                ? "generated.pdf"
                : req.outputPath().trim();
        if (!outputPath.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            outputPath = outputPath + ".pdf";
        }

        String format = req.format() == null ? "MARKDOWN" : req.format().trim().toUpperCase(Locale.ROOT);

        try {
            String html = switch (format) {
                case "HTML" -> wrapHtmlDocument(req.content());
                case "MARKDOWN", "MD", "" -> wrapHtmlDocument(renderMarkdown(req.content()));
                default -> throw new IllegalArgumentException("format must be MARKDOWN or HTML");
            };

            byte[] pdfBytes = renderPdf(html);
            FileDescriptor descriptor = sessionFileSystem.write(
                    area,
                    owner,
                    outputPath,
                    new ByteArrayInputStream(pdfBytes),
                    pdfBytes.length);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", descriptor.area().name());
            payload.put("path", descriptor.path());
            payload.put("name", extractFileName(descriptor.path()));
            payload.put("sizeBytes", descriptor.sizeBytes());
            payload.put("downloadUrl", descriptor.downloadUrl());
            boolean attachToChat = req.attachToChat() == null || req.attachToChat();
            if (attachToChat) {
                recordGeneratedAttachment(descriptor.path(), "application/pdf", descriptor.downloadUrl());
            }
            log.debug("EXIT createPdf: area={}, path={}, size={}",
                    descriptor.area(), descriptor.path(), descriptor.sizeBytes());
            return json(payload);
        } catch (Exception ex) {
            log.warn("createPdf failed [outputPath={}]: {}", outputPath, ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String fileExists(FileExistsRequest req) {
        log.debug("ENTER fileExists: area={}, path={}", req == null ? null : req.area(), req == null ? null : req.path());
        if (req == null || req.path() == null || req.path().isBlank()) {
            return error("path is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        boolean exists;
        try (InputStream in = sessionFileSystem.read(area, owner, req.path())) {
            exists = in != null;
        } catch (Exception ex) {
            exists = false;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("area", area.name());
        payload.put("path", req.path());
        payload.put("exists", exists);
        log.debug("EXIT fileExists: area={}, path={}, exists={}", area, req.path(), exists);
        return json(payload);
    }

    public String folderExists(FolderExistsRequest req) {
        log.debug("ENTER folderExists: area={}, path={}", req == null ? null : req.area(), req == null ? null : req.path());
        if (req == null || req.path() == null || req.path().isBlank()) {
            return error("path is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        boolean exists;
        try {
            sessionFileSystem.list(area, owner, req.path());
            exists = true;
        } catch (Exception ex) {
            exists = false;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("area", area.name());
        payload.put("path", req.path());
        payload.put("exists", exists);
        log.debug("EXIT folderExists: area={}, path={}, exists={}", area, req.path(), exists);
        return json(payload);
    }

    public String extractZip(ExtractZipRequest req) {
        log.debug("ENTER extractZip: area={}, archivePath={}, destinationPath={}",
                req == null ? null : req.area(),
                req == null ? null : req.archivePath(),
                req == null ? null : req.destinationPath());
        if (req == null || req.archivePath() == null || req.archivePath().isBlank()) {
            return error("archivePath is required");
        }
        if (req.destinationPath() == null || req.destinationPath().isBlank()) {
            return error("destinationPath is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;
        String destinationRoot = normalizePath(req.destinationPath());

        int fileCount = 0;
        int dirCount = 0;
        try (InputStream zipIn = sessionFileSystem.read(area, owner, req.archivePath());
             ZipInputStream zis = new ZipInputStream(zipIn, StandardCharsets.UTF_8)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = sanitizeZipEntry(entry.getName());
                if (entryName.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                String destinationPath = destinationRoot + "/" + entryName;
                String normalizedDestination = normalizePath(destinationPath);
                if (!normalizedDestination.startsWith(destinationRoot + "/")
                        && !normalizedDestination.equals(destinationRoot)) {
                    throw new IllegalArgumentException("Zip entry escapes destination root: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    sessionFileSystem.createDirectory(area, owner, normalizedDestination);
                    dirCount++;
                    zis.closeEntry();
                    continue;
                }

                String parent = parentPath(normalizedDestination);
                if (parent != null && !parent.isBlank()) {
                    sessionFileSystem.createDirectory(area, owner, parent);
                }

                ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                zis.transferTo(entryBytes);
                byte[] bytes = entryBytes.toByteArray();
                FileDescriptor descriptor = sessionFileSystem.write(
                        area,
                        owner,
                        normalizedDestination,
                        new ByteArrayInputStream(bytes),
                        bytes.length);
                fileCount++;

                boolean attachToChat = req.attachToChat() != null && req.attachToChat();
                if (attachToChat) {
                    recordGeneratedAttachment(descriptor.path(), inferMimeType(descriptor.path()), descriptor.downloadUrl());
                }
                zis.closeEntry();
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", area.name());
            payload.put("archivePath", req.archivePath());
            payload.put("destinationPath", destinationRoot);
            payload.put("filesExtracted", fileCount);
            payload.put("directoriesCreated", dirCount);
            log.debug("EXIT extractZip: area={}, archivePath={}, files={}, dirs={}",
                    area, req.archivePath(), fileCount, dirCount);
            return json(payload);
        } catch (Exception ex) {
            log.warn("extractZip failed [archivePath={}]: {}", req.archivePath(), ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String installCommand(InstallCommandRequest req) {
        log.debug("ENTER installCommand: area={}, binPath={}, command={}",
                req == null ? null : req.area(),
                req == null ? null : req.binPath(),
                req == null ? null : req.command());
        if (req == null || req.binPath() == null || req.binPath().isBlank()) {
            return error("binPath is required");
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        try {
            Path binAbsolute = sessionPathResolver.resolveAreaPath(area, owner, req.binPath());
            if (!Files.isDirectory(binAbsolute)) {
                return error("binPath must reference an existing directory");
            }

            Path requiredRoot = area == FileArea.SESSION
                    ? sessionPathResolver.toolsRoot(sessionUuid)
                    : sessionPathResolver.resolveAreaPath(FileArea.SHARED, null, "tools");
            if (!binAbsolute.startsWith(requiredRoot)) {
                return error("binPath must be under the tools folder");
            }

            if (req.command() != null && !req.command().isBlank()) {
                if (!commandExistsInDirectory(binAbsolute, req.command())) {
                    return error("command was not found in binPath");
                }
            }

            Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
            String existingPaths = env.getOrDefault(ProcessExecutionConfigResolver.ENV_COMMAND_PATHS, "");
            String updated = ProcessExecutionConfigResolver.normalizeCommandPathList(existingPaths, binAbsolute.toString());

            sessionEnvironmentService.setEnv(sessionUuid, ProcessExecutionConfigResolver.ENV_TOOLS_HOME,
                    sessionPathResolver.toolsRoot(sessionUuid).toString());
            sessionEnvironmentService.setEnv(sessionUuid, ProcessExecutionConfigResolver.ENV_COMMAND_PATHS, updated);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("sessionUuid", sessionUuid);
            payload.put("registeredBinPath", binAbsolute.toString());
            payload.put("command", req.command());
            payload.put("commandPaths", splitPathList(updated));
            log.debug("EXIT installCommand: session={}, registeredBinPath={}", sessionUuid, binAbsolute);
            return json(payload);
        } catch (Exception ex) {
            log.warn("installCommand failed [binPath={}]: {}", req.binPath(), ex.getMessage());
            return error(ex.getMessage());
        }
    }

    public String isCommandInstalled(IsCommandInstalledRequest req) {
        log.debug("ENTER isCommandInstalled: command={}", req == null ? null : req.command());
        if (req == null || req.command() == null || req.command().isBlank()) {
            return error("command is required");
        }

        String sessionUuid = resolveSessionUuid();
        ToolExecutionContext.bindSessionUuid(sessionUuid);

        Map<String, String> env = sessionEnvironmentService.getEnv(sessionUuid);
        String configuredPaths = env.getOrDefault(ProcessExecutionConfigResolver.ENV_COMMAND_PATHS, "");

        String normalizedCommand = ProcessExecutionConfigResolver.normalizeCommandName(req.command());
        Path matchedDirectory = null;
        Path matchedExecutable = null;

        for (String token : splitPathList(configuredPaths)) {
            try {
                Path directory = Path.of(token).toAbsolutePath().normalize();
                if (!directory.startsWith(sessionPathResolver.sessionRoot(sessionUuid))) {
                    continue;
                }
                Path executable = findExecutablePath(directory, normalizedCommand);
                if (executable != null) {
                    matchedDirectory = directory;
                    matchedExecutable = executable;
                    break;
                }
            } catch (Exception ignored) {
                // Ignore malformed path entries.
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("command", normalizedCommand);
        payload.put("installed", matchedExecutable != null);
        payload.put("searchPaths", splitPathList(configuredPaths));
        if (matchedDirectory != null) {
            payload.put("matchedPath", matchedDirectory.toString());
        }
        if (matchedExecutable != null) {
            payload.put("executable", matchedExecutable.toString());
        }

        log.debug("EXIT isCommandInstalled: command={}, installed={}", normalizedCommand, matchedExecutable != null);
        return json(payload);
    }

    public String resolveArchitecture(ResolveArchitectureRequest req) {
        log.debug("ENTER resolveArchitecture");

        String hostOsName = System.getProperty("os.name", "");
        String hostOsArch = System.getProperty("os.arch", "");
        String targetArchEnv = System.getenv("TARGETARCH");
        String detected = normalizeArchitecture(hostOsArch);
        String targetArchitecture = normalizeArchitecture(targetArchEnv);

        if ("unknown".equals(targetArchitecture)) {
            targetArchitecture = detected;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("hostOs", hostOsName);
        payload.put("hostArchitecture", hostOsArch);
        payload.put("detectedArchitecture", detected);
        payload.put("targetArchitecture", targetArchitecture);
        payload.put("targetArchitectureEnv", targetArchEnv);
        payload.put("inDocker", isRunningInDocker());

        log.debug("EXIT resolveArchitecture: detectedArchitecture={}, targetArchitecture={}", detected, targetArchitecture);
        return json(payload);
    }

    private static String renderMarkdown(String markdown) {
        return MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown));
    }

    private static String wrapHtmlDocument(String bodyHtml) {
                String html = bodyHtml == null ? "" : bodyHtml;
        return """
                                <html xmlns=\"http://www.w3.org/1999/xhtml\">
                <head>
                  <meta charset=\"utf-8\" />
                  <style>
                    body { font-family: Arial, Helvetica, sans-serif; font-size: 12pt; line-height: 1.4; }
                    h1,h2,h3 { margin: 0.4em 0 0.25em; }
                    p, ul, ol, pre, table { margin: 0.35em 0; }
                    code, pre { font-family: Menlo, Monaco, monospace; }
                    pre { background: #f4f4f4; padding: 8px; border-radius: 4px; }
                    table { border-collapse: collapse; width: 100%; }
                    th, td { border: 1px solid #ddd; padding: 6px; }
                  </style>
                </head>
                <body>
                """ + html + """
                </body>
                </html>
                """;
    }

    private static byte[] renderPdf(String html) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        }
    }

    private byte[] zipDirectory(FileArea area, String sessionUuid, String folderPath, List<String> files) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            String rootName = extractFileName(folderPath);
            if (files.isEmpty()) {
                zos.putNextEntry(new ZipEntry(rootName + "/"));
                zos.closeEntry();
            } else {
                for (String filePath : files) {
                    String relative = relativize(folderPath, filePath);
                    String entryName = rootName + "/" + relative;
                    zos.putNextEntry(new ZipEntry(entryName));
                    try (InputStream in = sessionFileSystem.read(area, sessionUuid, filePath)) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
            zos.finish();
            return baos.toByteArray();
        }
    }

    private List<String> collectFilePaths(FileArea area, String sessionUuid, String currentDir) throws Exception {
        List<FileNode> nodes = sessionFileSystem.list(area, sessionUuid, currentDir);
        List<String> paths = new ArrayList<>();
        for (FileNode node : nodes) {
            if (node.directory()) {
                paths.addAll(collectFilePaths(area, sessionUuid, node.path()));
            } else {
                paths.add(node.path());
            }
        }
        return paths;
    }

    private static String relativize(String root, String fullPath) {
        String normalizedRoot = normalizePath(root);
        String normalizedPath = normalizePath(fullPath);
        if (normalizedPath.equals(normalizedRoot)) {
            return extractFileName(normalizedPath);
        }
        if (normalizedPath.startsWith(normalizedRoot + "/")) {
            return normalizedPath.substring(normalizedRoot.length() + 1);
        }
        return extractFileName(normalizedPath);
    }

    private static String normalizePath(String value) {
        String normalized = value.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String sanitizeZipEntry(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return "";
        }

        String normalized = entryName.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.contains("..") || normalized.contains(":") || normalized.startsWith("/")) {
            throw new IllegalArgumentException("Invalid zip entry path: " + entryName);
        }
        return normalizePath(normalized);
    }

    private static String parentPath(String path) {
        int idx = path.lastIndexOf('/');
        if (idx <= 0) {
            return "";
        }
        return path.substring(0, idx);
    }

    private static List<String> splitPathList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (String token : PATH_SPLIT_PATTERN.split(raw)) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static boolean commandExistsInDirectory(Path directory, String command) {
        return findExecutablePath(directory, command) != null;
    }

    private static Path findExecutablePath(Path directory, String command) {
        if (directory == null || command == null || command.isBlank()) {
            return null;
        }
        if (!Files.isDirectory(directory)) {
            return null;
        }

        String normalized = ProcessExecutionConfigResolver.normalizeCommandName(command);
        for (String candidate : executableCandidates(normalized)) {
            Path resolved = directory.resolve(candidate).normalize();
            if (!resolved.startsWith(directory)) {
                continue;
            }
            if (Files.exists(resolved) && Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    private static List<String> executableCandidates(String normalizedCommand) {
        if (!WINDOWS) {
            return List.of(normalizedCommand);
        }

        List<String> candidates = new ArrayList<>();
        candidates.add(normalizedCommand);
        if (!normalizedCommand.endsWith(".exe")) {
            candidates.add(normalizedCommand + ".exe");
        }
        if (!normalizedCommand.endsWith(".cmd")) {
            candidates.add(normalizedCommand + ".cmd");
        }
        if (!normalizedCommand.endsWith(".bat")) {
            candidates.add(normalizedCommand + ".bat");
        }
        return candidates;
    }

    private static String normalizeArchitecture(String rawArch) {
        if (rawArch == null || rawArch.isBlank()) {
            return "unknown";
        }
        String normalized = rawArch.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "x64", "amd64", "x86_64" -> "amd64";
            case "arm64", "aarch64" -> "arm64";
            case "arm", "armv7", "armv7l" -> "arm";
            case "386", "x86", "i386", "i686" -> "386";
            default -> "unknown";
        };
    }

    private static boolean isRunningInDocker() {
        try {
            return Files.exists(Path.of("/.dockerenv"));
        } catch (Exception ex) {
            return false;
        }
    }

    private static FileArea parseArea(String rawArea) {
        if (rawArea == null || rawArea.isBlank()) {
            return FileArea.SESSION;
        }
        try {
            return FileArea.valueOf(rawArea.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return FileArea.SESSION;
        }
    }

    private static String resolveSessionUuid() {
        String fromContext = ToolExecutionContext.getSessionUuid();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        String fromMdc = MDC.get("sessionUuid");
        if (fromMdc != null && !fromMdc.isBlank() && !"<null>".equals(fromMdc)) {
            return fromMdc;
        }
        throw new IllegalStateException("No sessionUuid available in execution context");
    }

    private static String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "generated-file";
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String name = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        return name.isBlank() ? "generated-file" : name;
    }

    private static String buildDownloadUrl(FileArea area, String sessionUuid, String relativePath) {
        StringBuilder url = new StringBuilder("/api/session-files/download?area=")
                .append(area.name())
                .append("&path=")
                .append(URLEncoder.encode(relativePath, StandardCharsets.UTF_8));
        if (area == FileArea.SESSION && sessionUuid != null && !sessionUuid.isBlank()) {
            url.append("&sessionUuid=")
                    .append(URLEncoder.encode(sessionUuid, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private static boolean isTextFile(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt")
                || lower.endsWith(".md")
                || lower.endsWith(".json")
                || lower.endsWith(".yaml")
                || lower.endsWith(".yml")
                || lower.endsWith(".csv")
                || lower.endsWith(".xml")
                || lower.endsWith(".java")
                || lower.endsWith(".js")
                || lower.endsWith(".ts")
                || lower.endsWith(".html")
                || lower.endsWith(".css")
                || lower.endsWith(".log");
    }

    private static String inferMimeType(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (isTextFile(path)) return "text/plain";
        return "application/octet-stream";
    }

    private String error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "error");
        payload.put("message", (message == null || message.isBlank())
                ? "Unexpected file operation error"
                : message);
        return json(payload);
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("Failed to serialize JSON response: {}", ex.getMessage());
            return "{\"status\":\"error\",\"message\":\"JSON serialization failed\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private static void recordGeneratedAttachment(String path, String mimeType, String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            return;
        }
        Object existing = ToolExecutionContext.get(GENERATED_ATTACHMENTS_CONTEXT_KEY);
        List<Map<String, String>> items;
        if (existing instanceof List<?> list) {
            items = (List<Map<String, String>>) list;
        } else {
            items = new ArrayList<>();
            ToolExecutionContext.put(GENERATED_ATTACHMENTS_CONTEXT_KEY, items);
        }

        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("path", path == null ? "" : path);
        entry.put("mimeType", mimeType == null ? "application/octet-stream" : mimeType);
        entry.put("downloadUrl", downloadUrl);
        items.add(entry);
    }

    @SuppressWarnings("unchecked")
    private static void removeGeneratedAttachmentsForPaths(List<String> zippedFilePaths) {
        if (zippedFilePaths == null || zippedFilePaths.isEmpty()) {
            return;
        }
        Object existing = ToolExecutionContext.get(GENERATED_ATTACHMENTS_CONTEXT_KEY);
        if (!(existing instanceof List<?> list) || list.isEmpty()) {
            return;
        }

        Set<String> zipped = new HashSet<>();
        for (String path : zippedFilePaths) {
            zipped.add(normalizePath(path));
        }

        List<Map<String, String>> items = (List<Map<String, String>>) list;
        items.removeIf(item -> {
            if (item == null) {
                return false;
            }
            String path = item.get("path");
            return path != null && zipped.contains(normalizePath(path));
        });

        if (items.isEmpty()) {
            ToolExecutionContext.remove(GENERATED_ATTACHMENTS_CONTEXT_KEY);
        }
    }
}
