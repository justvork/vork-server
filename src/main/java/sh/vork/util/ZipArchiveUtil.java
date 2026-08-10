package sh.vork.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Small helper for creating and reading in-memory zip archives.
 */
public final class ZipArchiveUtil {

    private ZipArchiveUtil() {
    }

    public static byte[] write(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                String path = normalizeEntryPath(entry.getKey());
                if (path.isBlank()) {
                    continue;
                }
                ZipEntry zipEntry = new ZipEntry(path);
                zos.putNextEntry(zipEntry);
                byte[] bytes = entry.getValue() == null ? new byte[0] : entry.getValue();
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    public static Map<String, byte[]> read(byte[] zipBytes) throws IOException {
        return read(new ByteArrayInputStream(zipBytes));
    }

    public static Map<String, byte[]> read(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String path = normalizeEntryPath(entry.getName());
                if (path.isBlank()) {
                    zis.closeEntry();
                    continue;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                zis.transferTo(baos);
                entries.put(path, baos.toByteArray());
                zis.closeEntry();
            }
        }
        return entries;
    }

    private static String normalizeEntryPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid zip entry path: " + path);
        }
        return normalized;
    }
}
