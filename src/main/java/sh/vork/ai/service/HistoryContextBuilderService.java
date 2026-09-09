package sh.vork.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.protocol.RetainedContext;

/**
 * Shared context builder that shapes persisted chat history into Spring AI messages.
 *
 * <p>Recent history is replayed at full fidelity up to configurable limits, then
 * older assistant turns degrade to retained-context snapshots. Tool turns are
 * always replayed as metadata-only references and require readChatHistory to
 * fetch full output when needed.
 */
@Service
public class HistoryContextBuilderService {

    public static final int DEFAULT_MAX_FULL_HISTORY_ITEMS = 20;
    public static final int DEFAULT_MAX_FULL_HISTORY_TOKENS = 20_000;

    private final ObjectMapper objectMapper;
    private final int maxFullHistoryItems;
    private final int maxFullHistoryTokens;

    @Autowired
    public HistoryContextBuilderService(
            ObjectMapper objectMapper,
            @Value("${vork.ai.context.max-full-history-items:20}") int maxFullHistoryItems,
            @Value("${vork.ai.context.max-full-history-tokens:20000}") int maxFullHistoryTokens) {
        this.objectMapper = objectMapper;
        this.maxFullHistoryItems = maxFullHistoryItems > 0 ? maxFullHistoryItems : DEFAULT_MAX_FULL_HISTORY_ITEMS;
        this.maxFullHistoryTokens = maxFullHistoryTokens > 0 ? maxFullHistoryTokens : DEFAULT_MAX_FULL_HISTORY_TOKENS;
    }

    public List<Message> buildHistory(List<AiChatMessage> messages) {
        return buildHistory(messages, BuildOptions.includeAllTools());
    }

    public List<Message> buildHistory(List<AiChatMessage> messages, BuildOptions options) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        BuildOptions effectiveOptions = options == null ? BuildOptions.includeAllTools() : options;
        List<AiChatMessage> included = new ArrayList<>();
        for (AiChatMessage message : messages) {
            if (message == null || message.role() == null) {
                continue;
            }
            switch (message.role()) {
                case "USER", "EXTERNAL", "OUTGOING", "ASSISTANT" -> included.add(message);
                case "TOOL" -> {
                    String toolName = message.toolName();
                    if (effectiveOptions.includeAllToolMessages()
                            || (toolName != null
                                && effectiveOptions.visibleToolNames() != null
                                && effectiveOptions.visibleToolNames().contains(toolName))) {
                        included.add(message);
                    }
                }
                default -> {
                    // Skip control frames.
                }
            }
        }

        if (included.isEmpty()) {
            return List.of();
        }

        boolean[] assistantUseFullText = new boolean[included.size()];
        int itemCount = 0;
        long tokenCount = 0;
        boolean thresholdReached = false;

        for (int idx = included.size() - 1; idx >= 0; idx--) {
            AiChatMessage message = included.get(idx);
            boolean useFullAssistantText = "ASSISTANT".equals(message.role()) && !thresholdReached;
            String rendered = renderContextText(message, useFullAssistantText);

            itemCount++;
            tokenCount += estimateTokens(rendered);

            if ("ASSISTANT".equals(message.role())) {
                assistantUseFullText[idx] = useFullAssistantText;
            }

            if (!thresholdReached
                    && (itemCount >= maxFullHistoryItems || tokenCount >= maxFullHistoryTokens)) {
                thresholdReached = true;
            }
        }

        List<Message> history = new ArrayList<>(included.size());
        for (int idx = 0; idx < included.size(); idx++) {
            AiChatMessage message = included.get(idx);
            switch (message.role()) {
                case "USER" -> history.add(new UserMessage(valueOrEmpty(message.content())));
                case "EXTERNAL" -> history.add(new UserMessage(ExternalMessageProvenance.toWrappedEvidence(message)));
                case "OUTGOING" -> history.add(new UserMessage(OutgoingMessageProvenance.toWrappedEvidence(message)));
                case "ASSISTANT" -> {
                    if (assistantUseFullText[idx]) {
                        history.add(new AssistantMessage(valueOrEmpty(message.content())));
                    } else {
                        history.add(new AssistantMessage(renderAssistantRetainedContextSnapshot(message)));
                    }
                }
                case "TOOL" -> history.add(new AssistantMessage(renderToolMetadataSnapshot(message)));
                default -> {
                    // Filtered before this pass.
                }
            }
        }

        return history;
    }

    private String renderContextText(AiChatMessage message, boolean useFullAssistantText) {
        if (message == null || message.role() == null) {
            return "";
        }
        return switch (message.role()) {
            case "USER" -> valueOrEmpty(message.content());
            case "EXTERNAL" -> ExternalMessageProvenance.toWrappedEvidence(message);
            case "OUTGOING" -> OutgoingMessageProvenance.toWrappedEvidence(message);
            case "ASSISTANT" -> useFullAssistantText
                    ? valueOrEmpty(message.content())
                    : renderAssistantRetainedContextSnapshot(message);
            case "TOOL" -> renderToolMetadataSnapshot(message);
            default -> "";
        };
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1L, (text.length() + 3L) / 4L);
    }

    private String renderAssistantRetainedContextSnapshot(AiChatMessage message) {
        String messageUuid = message.uuid() == null ? "" : message.uuid();
        RetainedContext retainedContext = normalizeRetainedContext(message.retainedContext());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "assistant_retained_context");
        payload.put("messageUuid", messageUuid);
        payload.put("retainedContext", retainedContext);
        payload.put("fullResponseAccess", "Use readChatHistory with reference='uuid:" + messageUuid
                + "' when full assistant response text is required.");
        return toJson(payload);
    }

    private String renderToolMetadataSnapshot(AiChatMessage message) {
        String messageUuid = message.uuid() == null ? "" : message.uuid();
        String toolName = message.toolName() == null ? "unknown-tool" : message.toolName();
        String toolCallId = message.toolCallId() == null ? "pending-unknown" : message.toolCallId();
        String arguments = extractToolArguments(message.content());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tool_history_metadata");
        payload.put("messageUuid", messageUuid);
        payload.put("toolName", toolName);
        payload.put("toolCallId", toolCallId);
        payload.put("arguments", arguments);
        payload.put("fullResponseAccess", "Use readChatHistory with reference='uuid:" + messageUuid
                + "' when tool output is required.");
        return toJson(payload);
    }

    private static RetainedContext normalizeRetainedContext(RetainedContext retainedContext) {
        if (retainedContext == null) {
            return RetainedContext.empty();
        }
        return new RetainedContext(retainedContext.facts(), retainedContext.decisions(), retainedContext.unresolved());
    }

    private String extractToolArguments(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "{}";
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});
            Object arguments = payload.get("arguments");
            if (arguments == null) {
                return "{}";
            }
            if (arguments instanceof String s) {
                return s.isBlank() ? "{}" : s;
            }
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    public record BuildOptions(boolean includeAllToolMessages, Set<String> visibleToolNames) {
        public static BuildOptions includeAllTools() {
            return new BuildOptions(true, null);
        }

        public static BuildOptions filterTools(Set<String> visibleToolNames) {
            return new BuildOptions(false, visibleToolNames == null ? Set.of() : Set.copyOf(visibleToolNames));
        }
    }
}
