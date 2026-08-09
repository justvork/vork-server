package sh.vork.surface.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import sh.vork.ai.AiProvider;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.service.ChatService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillActivatedException;
import sh.vork.skill.SkillService;

import java.util.LinkedHashMap;
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
 * Executes surface-attached skills asynchronously and exposes execution state for polling.
 */
@Service
public class SurfaceSkillExecutionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SkillService skillService;
    private final ChatService chatService;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "surface-skill-exec");
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentHashMap<String, ExecutionContext> executions = new ConcurrentHashMap<>();

    public SurfaceSkillExecutionService(SkillService skillService,
                                        ChatService chatService) {
        this.skillService = skillService;
        this.chatService = chatService;
    }

    public ExecutionSnapshot start(String surfaceUuid,
                                   String executionSessionUuid,
                                   Skill skill,
                                   Map<String, Object> args,
                                   AiProvider provider) {
        String executionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        ExecutionSnapshot pending = new ExecutionSnapshot(
                executionId,
                surfaceUuid,
                executionSessionUuid,
                skill.uuid(),
                ExecutionState.PENDING,
                null,
                null,
                null,
                now,
                now,
                null);

        ExecutionContext context = new ExecutionContext(pending);
        executions.put(executionId, context);

        executor.submit(() -> runExecution(context, skill, args == null ? Map.of() : args, provider));
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
                              Skill skill,
                              Map<String, Object> args,
                              AiProvider provider) {
        long now = System.currentTimeMillis();
        setSnapshot(context, context.snapshot.withState(ExecutionState.RUNNING, now));

        try {
            ToolExecutionContext.bindSessionUuid(context.snapshot.executionSessionUuid());

            Map<String, String> normalizedArgs = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                Object value = entry.getValue();
                normalizedArgs.put(entry.getKey(), value == null ? "" : String.valueOf(value));
            }

            String skillOutput;
            try {
                skillOutput = skillService.executeSkill(skill.uuid(), normalizedArgs);
            } catch (SkillActivatedException activated) {
                skillOutput = chatService.executeSkillSubLoop(
                        context.snapshot.executionSessionUuid(),
                        List.of(),
                        activated,
                        provider);
            }

            Object resultPayload = parseExecutionOutput(skillOutput, skill.outputContentType());
            ExecutionSnapshot done = context.snapshot.withCompletion(
                    ExecutionState.COMPLETED,
                    now(),
                    skill.outputContentType(),
                    resultPayload,
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
                    message);
            setSnapshot(context, failed);
            context.completion.complete(failed);
        } finally {
            ToolExecutionContext.clear();
        }
    }

    private Object parseExecutionOutput(String raw, String outputContentType) {
        String contentType = outputContentType == null ? "none" : outputContentType.trim().toLowerCase();
        if (!"application/json".equals(contentType)) {
            return Map.of("raw", raw == null ? "" : raw);
        }

        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Skill returned empty output.");
        }

        try {
            Object payload = OBJECT_MAPPER.readValue(raw, Object.class);
            if (payload instanceof Map<?, ?> map && map.containsKey("status")) {
                String status = String.valueOf(map.get("status"));
                if (!"ok".equalsIgnoreCase(status) && !"completed".equalsIgnoreCase(status)) {
                    Object message = map.get("message");
                    throw new IllegalArgumentException(message == null
                            ? "Skill execution failed."
                            : String.valueOf(message));
                }
            }
            return payload;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Skill returned non-JSON output while outputContentType is application/json.");
        }
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

        private ExecutionContext(ExecutionSnapshot snapshot) {
            this.snapshot = snapshot;
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
            String skillUuid,
            ExecutionState state,
            String outputContentType,
            Object result,
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
                    skillUuid,
                    nextState,
                    outputContentType,
                    result,
                    error,
                    startedAt,
                    updatedAt,
                    completedAt);
        }

        public ExecutionSnapshot withCompletion(ExecutionState nextState,
                                                long updatedAt,
                                                String outputContentType,
                                                Object result,
                                                String error) {
            return new ExecutionSnapshot(
                    executionId,
                    surfaceUuid,
                    executionSessionUuid,
                    skillUuid,
                    nextState,
                    outputContentType,
                    result,
                    error,
                    startedAt,
                    updatedAt,
                    updatedAt);
        }
    }
}
