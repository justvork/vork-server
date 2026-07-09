package sh.vork.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Namespace-aware file system API for per-session sandboxes and shared exchange.
 */
public interface SessionFileSystem {

    FileDescriptor write(FileArea area,
                         String sessionUuid,
                         String relativePath,
                         InputStream content,
                         long sizeBytes) throws IOException;

    default FileDescriptor writeText(FileArea area,
                                     String sessionUuid,
                                     String relativePath,
                                     String content) throws IOException {
        byte[] bytes = content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return write(area, sessionUuid, relativePath, new java.io.ByteArrayInputStream(bytes), bytes.length);
    }

    InputStream read(FileArea area, String sessionUuid, String relativePath) throws IOException;

    List<FileNode> list(FileArea area, String sessionUuid, String relativeDir) throws IOException;

    void createDirectory(FileArea area, String sessionUuid, String relativeDir) throws IOException;

    void deleteFile(FileArea area, String sessionUuid, String relativePath) throws IOException;

    void deleteDirectory(FileArea area, String sessionUuid, String relativeDir) throws IOException;
}
