package sh.vork.filesystem;

/**
 * Lightweight file/folder metadata exposed by the session file system.
 *
 * @param name       filename/folder name (last path segment)
 * @param path       relative path inside the selected namespace
 * @param directory  true when this node is a directory
 * @param sizeBytes  file size in bytes (0 for directories)
 * @param modifiedAt epoch milliseconds last-modified
 */
public record FileNode(
        String name,
        String path,
        boolean directory,
        long sizeBytes,
        long modifiedAt
) {}
