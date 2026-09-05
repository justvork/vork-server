package sh.vork.ai.service;

import sh.vork.ai.entity.AiChatMessage;

/**
 * Adapts persisted EXTERNAL chat messages to model-supported roles while
 * preserving provenance and reducing authority confusion.
 */
public final class ExternalMessageProvenance {

    private ExternalMessageProvenance() {
    }

    public static String toWrappedEvidence(AiChatMessage message) {
        if (message == null) {
            return "";
        }
        String source = normalize(message.externalSource(), "unknown");
        String participant = normalize(message.externalParticipant(), "unknown");
        String content = message.content() == null ? "" : message.content();

        return "<external-message source=\"" + escapeAttribute(source) + "\" participant=\""
                + escapeAttribute(participant) + "\">\n"
                + content
                + "\n</external-message>";
    }

    public static String toWrappedEvidence(String content, String source, String participant) {
        String safeContent = content == null ? "" : content;
        String safeSource = normalize(source, "unknown");
        String safeParticipant = normalize(participant, "unknown");
        return "<external-message source=\"" + escapeAttribute(safeSource) + "\" participant=\""
                + escapeAttribute(safeParticipant) + "\">\n"
                + safeContent
                + "\n</external-message>";
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
