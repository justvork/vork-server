package sh.vork.filesystem;

/**
 * Result metadata for write operations.
 *
 * @param area        target namespace
 * @param sessionUuid owning session UUID for SESSION area, null for SHARED
 * @param path        relative path within namespace
 * @param sizeBytes   bytes written
 * @param downloadUrl application-relative URL for retrieval
 */
public record FileDescriptor(
        FileArea area,
        String sessionUuid,
        String path,
        long sizeBytes,
        String downloadUrl
) {}
