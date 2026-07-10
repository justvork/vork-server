package sh.vork.storage;

import java.util.Set;

/**
 * MIME type classifier for media that can be consumed by multimodal AI providers.
 */
public final class AiMimeTypeSupport {

    private static final Set<String> SUPPORTED_EXACT = Set.of("application/pdf");

    private AiMimeTypeSupport() {
    }

    public static boolean isAiSupported(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String lower = mimeType.toLowerCase();
        return lower.startsWith("image/")
                || lower.startsWith("audio/")
                || lower.startsWith("video/")
                || lower.startsWith("text/")
                || SUPPORTED_EXACT.contains(lower);
    }
}
