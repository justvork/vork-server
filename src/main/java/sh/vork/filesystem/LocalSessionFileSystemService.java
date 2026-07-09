package sh.vork.filesystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Local filesystem-backed implementation of {@link SessionFileSystem}.
 *
 * <p>Namespaces:
 * <ul>
 *   <li>SESSION: {baseDir}/sessions/{sessionUuid}/...</li>
 *   <li>SHARED:  {baseDir}/shared/...</li>
 * </ul>
 */
@Service
public class LocalSessionFileSystemService implements SessionFileSystem {

    private static final Logger log = LoggerFactory.getLogger(LocalSessionFileSystemService.class);

    private final Path baseDir;

    public LocalSessionFileSystemService(
            @Value("${vork.fs.base-dir:${storage.base-dir:conf.d/files}/fs}") String baseDirPath) throws IOException {
        this.baseDir = Paths.get(baseDirPath).toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir.resolve("sessions"));
        Files.createDirectories(this.baseDir.resolve("shared"));
        log.info("Session file system initialised at {}", this.baseDir);
    }

    @Override
    public FileDescriptor write(FileArea area,
                                String sessionUuid,
                                String relativePath,
                                InputStream content,
                                long sizeBytes) throws IOException {
        Path target = resolveTarget(area, sessionUuid, relativePath);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);

        long written = sizeBytes >= 0 ? sizeBytes : Files.size(target);
        return new FileDescriptor(
                area,
                area == FileArea.SESSION ? sessionUuid : null,
                normalizeRelativePath(relativePath),
                written,
                buildDownloadUrl(area, sessionUuid, normalizeRelativePath(relativePath)));
    }

    @Override
    public InputStream read(FileArea area, String sessionUuid, String relativePath) throws IOException {
        Path target = resolveTarget(area, sessionUuid, relativePath);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            throw new IOException("File not found: " + normalizeRelativePath(relativePath));
        }
        return Files.newInputStream(target);
    }

    @Override
    public List<FileNode> list(FileArea area, String sessionUuid, String relativeDir) throws IOException {
        Path root = resolveDirectory(area, sessionUuid, relativeDir);
        if (!Files.exists(root)) {
            return List.of();
        }

        String baseRelative = normalizeRelativeDirectory(relativeDir);
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(path -> toNode(baseRelative, path))
                    .toList();
        }
    }

    @Override
    public void createDirectory(FileArea area, String sessionUuid, String relativeDir) throws IOException {
        Path dir = resolveDirectory(area, sessionUuid, relativeDir);
        Files.createDirectories(dir);
    }

    @Override
    public void deleteFile(FileArea area, String sessionUuid, String relativePath) throws IOException {
        Path target = resolveTarget(area, sessionUuid, relativePath);
        if (Files.isDirectory(target)) {
            throw new IOException("Path is a directory: " + normalizeRelativePath(relativePath));
        }
        Files.deleteIfExists(target);
    }

    @Override
    public void deleteDirectory(FileArea area, String sessionUuid, String relativeDir) throws IOException {
        if (relativeDir == null || relativeDir.isBlank()) {
            throw new IllegalArgumentException("relativeDir is required");
        }
        Path dir = resolveDirectory(area, sessionUuid, relativeDir);
        if (!Files.exists(dir)) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("Path is not a directory: " + normalizeRelativeDirectory(relativeDir));
        }

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }

    private FileNode toNode(String baseRelative, Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            long size = directory ? 0L : Files.size(path);
            long modified = Files.getLastModifiedTime(path).toMillis();
            String name = path.getFileName().toString();
            String rel = baseRelative.isBlank() ? name : (baseRelative + "/" + name);
            return new FileNode(name, rel, directory, size, modified);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private Path resolveTarget(FileArea area, String sessionUuid, String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        Path root = areaRoot(area, sessionUuid);
        Path resolved = root.resolve(normalized).normalize();
        ensureInsideRoot(root, resolved);
        return resolved;
    }

    private Path resolveDirectory(FileArea area, String sessionUuid, String relativeDir) {
        String normalized = normalizeRelativeDirectory(relativeDir);
        Path root = areaRoot(area, sessionUuid);
        Path resolved = normalized.isBlank() ? root : root.resolve(normalized).normalize();
        ensureInsideRoot(root, resolved);
        return resolved;
    }

    private Path areaRoot(FileArea area, String sessionUuid) {
        Objects.requireNonNull(area, "area is required");
        return switch (area) {
            case SHARED -> baseDir.resolve("shared").normalize();
            case SESSION -> {
                String sid = sanitizeSessionUuid(sessionUuid);
                if (sid.isBlank()) {
                    throw new IllegalArgumentException("sessionUuid is required for SESSION area");
                }
                yield baseDir.resolve("sessions").resolve(sid).normalize();
            }
        };
    }

    private static void ensureInsideRoot(Path root, Path resolved) {
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes sandbox root");
        }
    }

    private static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath is required");
        }
        String candidate = relativePath.replace('\\', '/').trim();
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("relativePath is required");
        }
        if (candidate.contains("..")) {
            throw new IllegalArgumentException("relativePath must not contain '..'");
        }
        return candidate;
    }

    private static String normalizeRelativeDirectory(String relativeDir) {
        if (relativeDir == null || relativeDir.isBlank()) {
            return "";
        }
        String candidate = relativeDir.replace('\\', '/').trim();
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        if (candidate.contains("..")) {
            throw new IllegalArgumentException("relativeDir must not contain '..'");
        }
        if (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private static String sanitizeSessionUuid(String sessionUuid) {
        if (sessionUuid == null) {
            return "";
        }
        return sessionUuid.replaceAll("[^a-zA-Z0-9._-]", "_");
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
}
