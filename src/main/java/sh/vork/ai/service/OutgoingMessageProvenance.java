package sh.vork.ai.service;

import sh.vork.ai.entity.AiChatMessage;

import java.util.Map;

/**
 * Adapts persisted OUTGOING chat messages to model-supported roles while
 * preserving external-delivery provenance.
 */
public final class OutgoingMessageProvenance {

    private OutgoingMessageProvenance() {
    }

    public static String toWrappedEvidence(AiChatMessage message) {
        if (message == null) {
            return "";
        }
        Map<String, String> metadata = message.messageMetadata();
        String channel = fromMetadata(metadata, "mediaType", normalize(message.externalSource(), "unknown"));
        String participant = normalize(message.externalParticipant(), "unknown");
        String destination = fromMetadata(metadata, "destination", "unknown");
        String providerConfigId = fromMetadata(metadata, "providerConfigId", "");
        String title = fromMetadata(metadata, "title", "");
        String bodyContentType = fromMetadata(metadata, "bodyContentType", "text/plain");
        String deliveryState = fromMetadata(metadata, "deliveryState", "SENT");
        String providerReferenceId = fromMetadata(metadata, "providerMessageReferenceId", "");
        String content = message.content() == null ? "" : message.content();

        StringBuilder out = new StringBuilder();
        out.append("<outgoing-message channel=\"")
                .append(escapeAttribute(channel))
                .append("\" participant=\"")
                .append(escapeAttribute(participant))
                .append("\" destination=\"")
                .append(escapeAttribute(destination))
                .append("\" body-content-type=\"")
                .append(escapeAttribute(bodyContentType))
                .append("\" delivery-state=\"")
                .append(escapeAttribute(deliveryState))
                .append("\"");
        if (!providerConfigId.isBlank()) {
            out.append(" provider-config-id=\"").append(escapeAttribute(providerConfigId)).append("\"");
        }
        if (!providerReferenceId.isBlank()) {
            out.append(" provider-reference-id=\"").append(escapeAttribute(providerReferenceId)).append("\"");
        }
        if (!title.isBlank()) {
            out.append(" title=\"").append(escapeAttribute(title)).append("\"");
        }
        out.append(">\n").append(content).append("\n</outgoing-message>");
        return out.toString();
    }

    private static String fromMetadata(Map<String, String> metadata, String key, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
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
