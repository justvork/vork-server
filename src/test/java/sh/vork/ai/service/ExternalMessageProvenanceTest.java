package sh.vork.ai.service;

import org.junit.jupiter.api.Test;
import sh.vork.ai.entity.AiChatMessage;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalMessageProvenanceTest {

    @Test
    void wrapsExternalMessageWithSourceParticipantAndBody() {
        AiChatMessage message = new AiChatMessage(
                "m1",
                "EXTERNAL",
                "Invoice 123 is overdue.",
                System.currentTimeMillis(),
                null,
                "email",
                "accounts@example.com");

        String wrapped = ExternalMessageProvenance.toWrappedEvidence(message);

        assertTrue(wrapped.contains("<external-message"));
        assertTrue(wrapped.contains("source=\"email\""));
        assertTrue(wrapped.contains("participant=\"accounts@example.com\""));
        assertTrue(wrapped.contains("Invoice 123 is overdue."));
        assertTrue(wrapped.contains("</external-message>"));
    }
}
