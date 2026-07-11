package sh.vork.ai.process;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import sh.vork.filesystem.FileArea;

/**
 * Resolves and validates local filesystem paths for session-scoped tool execution.
 */
@Component
public class SessionPathResolver {

    private final Path baseDir;

    public SessionPathResolver(@Value("${vork.fs.base-dir:${storage.base-dir:conf.d/files}/fs}") String baseDirPath) {
        this.baseDir = Paths.get(baseDirPath).toAbsolutePath().normalize();
    }

    public SessionPathResolver() {
        this("conf.d/files/fs");
    }

    public Path toolsRoot(String sessionUuid) {
        return sessionRoot(sessionUuid).resolve("tools").normalize();
    }

    public Path sessionRoot(String sessionUuid) {
        return baseDir.resolve("sessions").resolve(sanitizeSessionUuid(sessionUuid)).normalize();
    }

    public Path resolveAreaPath(FileArea area, String sessionUuid, String relativePath) {
        Path root = area == FileArea.SESSION
                ? sessionRoot(sessionUuid)
                : baseDir.resolve("shared").normalize();

        String normalizedRelative = normalizeRelativePath(relativePath);
        Path resolved = root.resolve(normalizedRelative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes sandbox root");
        }
        return resolved;
    }

    public Path baseDir() {
        return baseDir;
    }

    private static String sanitizeSessionUuid(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            throw new IllegalArgumentException("sessionUuid is required");
        }
        return sessionUuid.replaceAll("[^a-zA-Z0-9._-]", "_");
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
}
