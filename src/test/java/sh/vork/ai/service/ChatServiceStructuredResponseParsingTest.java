package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.entity.AiSession;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.relay.RelayEncryptionService;
import sh.vork.relay.RelayHttpClient;
import sh.vork.scheduling.service.SystemNotificationService;
import sh.vork.setup.SystemSettingsService;

class ChatServiceStructuredResponseParsingTest {

    @Test
    void extractTextResponse_recoversMalformedEnvelopeWithMultilineTextResponse() {
        ChatService chatService = new ChatService(
                new MapDatabaseRepository<>(AiSession.class),
                new MapDatabaseRepository<>(AgentTemplate.class),
                mock(AiOrchestrationService.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run,
                mock(RelayEncryptionService.class),
                mock(RelayHttpClient.class),
                mock(SystemSettingsService.class),
                null);

        String raw = """
                {
                  \"status\": \"FINISHED_TURN\",
                  \"textResponse\": \"### 🚀 Affirmative, Commander!

                All systems are green, contracts are verified, and the postcode journey planner surface is fully optimized and operational.\",
                  \"targetAgent\": null,
                  \"delegationInstructions\": null
                }
                """;

        String extracted = chatService.extractTextResponse(raw);
        assertTrue(extracted.startsWith("### 🚀 Affirmative, Commander!"));
        assertTrue(extracted.contains("All systems are green"));
    }
}
