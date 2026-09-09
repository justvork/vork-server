package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.protocol.RetainedContext;

class HistoryContextBuilderServiceTest {

    @Test
    void buildHistory_keepsRecentAssistantFullTextBeforeThreshold() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        HistoryContextBuilderService service = new HistoryContextBuilderService(objectMapper, 20, 20_000);

        List<AiChatMessage> messages = List.of(
                new AiChatMessage("u1", "USER", "hello", System.currentTimeMillis(), null),
                new AiChatMessage("a1", "ASSISTANT", "full assistant text", System.currentTimeMillis(), null,
                        null, null, null, null, null, null,
                        new RetainedContext(List.of("fact-1"), List.of(), List.of())));

        List<Message> history = service.buildHistory(messages);

        assertEquals(2, history.size());
        assertEquals("hello", history.get(0).getText());
        assertEquals("full assistant text", history.get(1).getText());
    }

    @Test
    void buildHistory_switchesOlderAssistantToRetainedContextAfterThreshold() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        HistoryContextBuilderService service = new HistoryContextBuilderService(objectMapper, 2, 20_000);

        List<AiChatMessage> messages = List.of(
                new AiChatMessage("u1", "USER", "user-1", System.currentTimeMillis(), null),
                new AiChatMessage("a-old", "ASSISTANT", "old assistant full", System.currentTimeMillis(), null,
                        null, null, null, null, null, null,
                        new RetainedContext(List.of("old-fact"), List.of(), List.of())),
                new AiChatMessage("u2", "USER", "user-2", System.currentTimeMillis(), null),
                new AiChatMessage("a-new", "ASSISTANT", "new assistant full", System.currentTimeMillis(), null,
                        null, null, null, null, null, null,
                        new RetainedContext(List.of("new-fact"), List.of(), List.of())));

        List<Message> history = service.buildHistory(messages);

        assertEquals(4, history.size());
        assertEquals("new assistant full", history.get(3).getText());

        JsonNode oldAssistant = objectMapper.readTree(history.get(1).getText());
        assertEquals("assistant_retained_context", oldAssistant.path("kind").asText());
        assertEquals("a-old", oldAssistant.path("messageUuid").asText());
        assertTrue(oldAssistant.path("retainedContext").isObject());
    }

    @Test
    void buildHistory_alwaysRendersToolAsMetadataReference() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        HistoryContextBuilderService service = new HistoryContextBuilderService(objectMapper, 20, 20_000);

        String toolPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "responses", List.of(java.util.Map.of("responseData", "sensitive tool output")),
                "arguments", "{\"query\":\"status\"}"));

        List<AiChatMessage> messages = List.of(
                new AiChatMessage("u1", "USER", "check status", System.currentTimeMillis(), null),
                new AiChatMessage("t1", "TOOL", toolPayload, System.currentTimeMillis(), null,
                        null, "call-1", "checkStatus"));

        List<Message> history = service.buildHistory(messages);

        assertEquals(2, history.size());
        JsonNode toolMeta = objectMapper.readTree(history.get(1).getText());
        assertEquals("tool_history_metadata", toolMeta.path("kind").asText());
        assertEquals("t1", toolMeta.path("messageUuid").asText());
        assertEquals("checkStatus", toolMeta.path("toolName").asText());
        assertEquals("call-1", toolMeta.path("toolCallId").asText());
        assertEquals("{\"query\":\"status\"}", toolMeta.path("arguments").asText());
        assertTrue(toolMeta.path("fullResponseAccess").asText().contains("readChatHistory"));
    }
}
