package sh.vork.ai.service;

import org.junit.jupiter.api.Test;
import sh.vork.ai.entity.AiChatMessage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OutgoingMessageProvenanceTest {

    @Test
    void wrapsOutgoingMessageWithChannelParticipantDestinationAndBody() {
        AiChatMessage message = new AiChatMessage(
                "m1",
                "OUTGOING",
                "Thanks Jane. I have received the invoice.",
                System.currentTimeMillis(),
                null,
            null,
            null,
            null,
                "Email",
                "Jane Swift",
                Map.of(
                        "mediaType", "EMAIL_ADDRESS",
                        "destination", "jane@example.com",
                        "providerConfigId", "provider-1",
                        "deliveryState", "SENT",
                        "title", "Invoice Confirmation",
                        "bodyContentType", "text/plain"
                ));

        String wrapped = OutgoingMessageProvenance.toWrappedEvidence(message);

        assertTrue(wrapped.contains("<outgoing-message"));
        assertTrue(wrapped.contains("channel=\"EMAIL_ADDRESS\""));
        assertTrue(wrapped.contains("participant=\"Jane Swift\""));
        assertTrue(wrapped.contains("destination=\"jane@example.com\""));
        assertTrue(wrapped.contains("provider-config-id=\"provider-1\""));
        assertTrue(wrapped.contains("title=\"Invoice Confirmation\""));
        assertTrue(wrapped.contains("Thanks Jane. I have received the invoice."));
        assertTrue(wrapped.contains("</outgoing-message>"));
    }
}
