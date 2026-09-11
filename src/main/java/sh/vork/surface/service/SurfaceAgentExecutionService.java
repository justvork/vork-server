package sh.vork.surface.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.ChatService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes surface-assigned agents asynchronously and exposes polling snapshots.
 */
@Service
public class SurfaceAgentExecutionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SurfaceService surfaceService;
    private final ChatService chatService;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "surface-agent-exec");
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentHashMap<String, ExecutionContext> executions = new ConcurrentHashMap<>();

    public SurfaceAgentExecutionService(SurfaceService surfaceService,
                                        ChatService chatService) {
        this.surfaceService = surfaceService;
        this.chatService = chatService;
    }

    public ExecutionSnapshot start(String surfaceUuid,
                                   String username,
                                   String agentTemplateId,
                                   String prompt,
                                   Object outputSchema) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required.");
        }
        JsonNode schemaNode = parseSchema(outputSchema);

        AgentTemplate template = surfaceService.resolveAttachedSurfaceAgent(surfaceUuid, agentTemplateId);
        AiSession executionSession = surfaceService.ensureExecutionSession(surfaceUuid, username);

        String executionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ExecutionSnapshot pending = new ExecutionSnapshot(
                executionId,
                surfaceUuid,
                executionSession.uuid(),
                template.uuid(),
                ExecutionState.PENDING,
                null,
                null,
                null,
            null,
                now,
                now,
                null);

        ExecutionContext context = new ExecutionContext(pending, schemaNode, prompt);
        executions.put(executionId, context);

        executor.submit(() -> runExecution(context, template, executionSession));
        return pending;
    }

    public ExecutionSnapshot poll(String surfaceUuid, String executionId, long waitMs) {
        ExecutionContext context = executions.get(executionId);
        if (context == null || !context.snapshot.surfaceUuid().equals(surfaceUuid)) {
            throw new IllegalArgumentException("Execution not found: " + executionId);
        }

        ExecutionSnapshot current = context.snapshot;
        if (current.state().isTerminal()) {
            return current;
        }

        long boundedWait = Math.max(0, Math.min(waitMs, 30_000L));
        if (boundedWait == 0) {
            return current;
        }

        try {
            return context.completion.get(boundedWait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return context.snapshot;
        } catch (Exception ex) {
            return context.snapshot;
        }
    }

    private void runExecution(ExecutionContext context,
                              AgentTemplate template,
                              AiSession executionSession) {
        setSnapshot(context, context.snapshot.withState(ExecutionState.RUNNING, now()));

        try {
            String switched = chatService.switchActiveAgentById(executionSession.uuid(), template.uuid());
            if (switched == null || switched.isBlank()) {
                throw new IllegalArgumentException("Failed to activate surface agent for execution.");
            }

            AiProvider provider;
            try {
                provider = AiProvider.valueOf(executionSession.provider());
            } catch (Exception ex) {
                provider = AiProvider.GEMINI;
            }

            String constrainedPrompt = buildSchemaConstrainedPrompt(context.prompt, context.schemaNode);
            AiChatMessage response = chatService.sendMessage(
                    executionSession.uuid(),
                    constrainedPrompt,
                    List.of(),
                    provider,
                    false);

            String textResponse = response == null || response.content() == null ? "" : response.content();
            JsonNode responseObject = extractResponseObject(textResponse);
            List<String> issues = new ArrayList<>();
            validateJsonAgainstSchema(responseObject, context.schemaNode, "$", issues);
            if (!issues.isEmpty()) {
                throw new IllegalArgumentException("Agent output schema mismatch: " + issues.getFirst());
            }

            Object resultObject = OBJECT_MAPPER.convertValue(responseObject, Object.class);
            ExecutionSnapshot done = context.snapshot.withCompletion(
                    ExecutionState.COMPLETED,
                    now(),
                    "application/json",
                    resultObject,
                    textResponse,
                    null);
            setSnapshot(context, done);
            context.completion.complete(done);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            ExecutionSnapshot failed = context.snapshot.withCompletion(
                    ExecutionState.FAILED,
                    now(),
                    null,
                    null,
                    null,
                    message);
            setSnapshot(context, failed);
            context.completion.complete(failed);
        }
    }

    private static JsonNode parseSchema(Object outputSchema) {
        if (outputSchema == null) {
            throw new IllegalArgumentException("outputSchema is required.");
        }
        try {
            if (outputSchema instanceof String text) {
                if (text.isBlank()) {
                    throw new IllegalArgumentException("outputSchema is required.");
                }
                JsonNode schema = OBJECT_MAPPER.readTree(text);
                if (!schema.isObject() && !schema.isBoolean()) {
                    throw new IllegalArgumentException("outputSchema must be a JSON object or boolean schema.");
                }
                return schema;
            }
            JsonNode schema = OBJECT_MAPPER.valueToTree(outputSchema);
            if (!schema.isObject() && !schema.isBoolean()) {
                throw new IllegalArgumentException("outputSchema must be a JSON object or boolean schema.");
            }
            return schema;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("outputSchema must be valid JSON.");
        }
    }

    private static String buildSchemaConstrainedPrompt(String prompt, JsonNode schemaNode) {
        String schemaJson = schemaNode.toPrettyString();
        return "Surface agent execution request:\n"
                + prompt + "\n\n"
                + "MANDATORY OUTPUT CONTRACT:\n"
                + "Return only JSON, no markdown fences.\n"
                + "Prefer returning an envelope with a top-level responseObject field containing the final result object.\n"
                + "If you do not use responseObject, return the final result object directly.\n"
                + "The final result object MUST match this JSON Schema exactly:\n"
                + schemaJson + "\n\n"
                + "Do not include explanation outside the JSON output.";
    }

    private static JsonNode extractResponseObject(String textResponse) {
        if (textResponse == null || textResponse.isBlank()) {
            throw new IllegalArgumentException("Agent returned empty output.");
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(textResponse);
            if (root != null && root.isObject()) {
                JsonNode nested = root.get("responseObject");
                if (nested != null && !nested.isNull()) {
                    return nested;
                }
                JsonNode textField = root.get("textResponse");
                if (textField != null && textField.isTextual()) {
                    JsonNode parsedText = OBJECT_MAPPER.readTree(textField.asText());
                    if (parsedText != null) {
                        return parsedText;
                    }
                }
            }
            return root;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Agent returned non-JSON output.");
        }
    }

    private static void validateJsonAgainstSchema(JsonNode value, JsonNode schema, String path, List<String> issues) {
        if (schema == null || issues == null) {
            return;
        }
        if (schema.isBoolean()) {
            if (!schema.booleanValue()) {
                issues.add(path + " is disallowed by boolean schema false.");
            }
            return;
        }
        if (!schema.isObject()) {
            return;
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null && !matchesDeclaredType(value, typeNode)) {
            issues.add(path + " does not match schema type " + typeNode + ".");
            return;
        }

        if (value != null && value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode field : required) {
                    if (field.isTextual() && !value.has(field.textValue())) {
                        issues.add(path + "." + field.textValue() + " is required.");
                    }
                }
            }

            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fields().forEachRemaining(entry -> {
                    JsonNode child = value.get(entry.getKey());
                    if (child != null) {
                        validateJsonAgainstSchema(child, entry.getValue(), path + "." + entry.getKey(), issues);
                    }
                });
            }

            JsonNode additionalProperties = schema.get("additionalProperties");
            if (additionalProperties != null && additionalProperties.isBoolean() && !additionalProperties.booleanValue()) {
                JsonNode propertiesNode = schema.get("properties");
                value.fieldNames().forEachRemaining(name -> {
                    if (propertiesNode == null || !propertiesNode.has(name)) {
                        issues.add(path + "." + name + " is not allowed by additionalProperties=false.");
                    }
                });
            }
        }

        if (value != null && value.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int i = 0; i < value.size(); i++) {
                    validateJsonAgainstSchema(value.get(i), items, path + "[" + i + "]", issues);
                }
            }
        }
    }

    private static boolean matchesDeclaredType(JsonNode value, JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return matchesSingleType(value, typeNode.textValue());
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                if (candidate.isTextual() && matchesSingleType(value, candidate.textValue())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesSingleType(JsonNode value, String schemaType) {
        if (schemaType == null) {
            return true;
        }
        return switch (schemaType) {
            case "object" -> value != null && value.isObject();
            case "array" -> value != null && value.isArray();
            case "string" -> value != null && value.isTextual();
            case "integer" -> value != null && value.isIntegralNumber();
            case "number" -> value != null && value.isNumber();
            case "boolean" -> value != null && value.isBoolean();
            case "null" -> value == null || value.isNull();
            default -> true;
        };
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void setSnapshot(ExecutionContext context, ExecutionSnapshot snapshot) {
        context.snapshot = snapshot;
    }

    private static final class ExecutionContext {
        private volatile ExecutionSnapshot snapshot;
        private final CompletableFuture<ExecutionSnapshot> completion = new CompletableFuture<>();
        private final JsonNode schemaNode;
        private final String prompt;

        private ExecutionContext(ExecutionSnapshot snapshot, JsonNode schemaNode, String prompt) {
            this.snapshot = snapshot;
            this.schemaNode = schemaNode;
            this.prompt = prompt;
        }
    }

    public enum ExecutionState {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED;
        }
    }

    public record ExecutionSnapshot(
            String executionId,
            String surfaceUuid,
            String executionSessionUuid,
            String agentTemplateId,
            ExecutionState state,
            String outputContentType,
            Object result,
            String textResponse,
            String error,
            long startedAt,
            long updatedAt,
            Long completedAt
    ) {
        public ExecutionSnapshot withState(ExecutionState nextState, long updatedAt) {
            return new ExecutionSnapshot(
                    executionId,
                    surfaceUuid,
                    executionSessionUuid,
                    agentTemplateId,
                    nextState,
                    outputContentType,
                    result,
                    textResponse,
                    error,
                    startedAt,
                    updatedAt,
                    completedAt);
        }

        public ExecutionSnapshot withCompletion(ExecutionState nextState,
                                                long updatedAt,
                                                String outputContentType,
                                                Object result,
                                                String textResponse,
                                                String error) {
            return new ExecutionSnapshot(
                    executionId,
                    surfaceUuid,
                    executionSessionUuid,
                    agentTemplateId,
                    nextState,
                    outputContentType,
                    result,
                    textResponse,
                    error,
                    startedAt,
                    updatedAt,
                    updatedAt);
        }
    }
}
