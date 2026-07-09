package sh.vork.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSessionFileSystemServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsSessionFileInsideSandbox() throws Exception {
        LocalSessionFileSystemService fs = new LocalSessionFileSystemService(tempDir.toString());

        byte[] payload = "hello-session".getBytes(StandardCharsets.UTF_8);
        FileDescriptor descriptor = fs.write(
                FileArea.SESSION,
                "session-123",
                "notes/output.txt",
                new ByteArrayInputStream(payload),
                payload.length);

        assertEquals(FileArea.SESSION, descriptor.area());
        assertEquals("session-123", descriptor.sessionUuid());
        assertEquals("notes/output.txt", descriptor.path());
        assertTrue(descriptor.downloadUrl().contains("area=SESSION"));

        String loaded = new String(fs.read(FileArea.SESSION, "session-123", "notes/output.txt").readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("hello-session", loaded);
    }

    @Test
    void blocksPathTraversalOutsideSandbox() throws Exception {
        LocalSessionFileSystemService fs = new LocalSessionFileSystemService(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () -> fs.write(
                FileArea.SESSION,
                "session-123",
                "../escape.txt",
                new ByteArrayInputStream(new byte[]{1}),
                1));

        assertThrows(IllegalArgumentException.class, () -> fs.list(
                FileArea.SESSION,
                "session-123",
                "../../"));
    }

    @Test
    void listsAndDeletesSharedFiles() throws Exception {
        LocalSessionFileSystemService fs = new LocalSessionFileSystemService(tempDir.toString());

        fs.writeText(FileArea.SHARED, null, "exchange/brief.txt", "hello-shared");
        List<FileNode> nodes = fs.list(FileArea.SHARED, null, "exchange");
        assertEquals(1, nodes.size());
        assertEquals("brief.txt", nodes.get(0).name());
        assertFalse(nodes.get(0).directory());

        fs.deleteFile(FileArea.SHARED, null, "exchange/brief.txt");
        assertTrue(fs.list(FileArea.SHARED, null, "exchange").isEmpty());
    }
}
