package sh.vork.ai.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Structured output contract for every AI generation turn in the Vork multi-agent loop.
 *
 * <p>Every agent response in the orchestration cycle <em>must</em> be valid JSON
 * matching this schema.  {@link sh.vork.ai.service.ChatService} parses the model's
 * raw text output into this record and uses {@code status} to drive the handoff
 * state machine:
 *
 * <ul>
 *   <li>{@code "FINISHED_TURN"} — the agent has completed its goal.  The
 *       {@code textResponse} is returned to the user as the final reply.  The
 *       session's active agent remains unchanged.</li>
 *   <li>{@code "DELEGATE_TURN"} — the agent wants to switch control to a
 *       different agent.  The orchestrator resolves {@code targetAgent} by name,
 *       updates the session's {@code activeAgentTemplateId}, broadcasts an
 *       {@code AGENT_TRANSITION} event, and starts a new generation pass using
 *       {@code delegationInstructions} as the prompt.  The agent stays active
 *       until the user or the AI explicitly switches again.</li>
 *   <li>{@code "CONTINUE_TURN"} — the agent is making progress.  {@code textResponse}
 *       is broadcast as an interim update and the loop continues.</li>
 * </ul>
 *
 * @param status                 {@code "FINISHED_TURN"}, {@code "DELEGATE_TURN"}, or {@code "CONTINUE_TURN"}
 * @param textResponse           human-readable progress or result message; surfaced
 *                               to the user on agent switch and returned as the final
 *                               reply when the agent finishes
 * @param targetAgent            exact display name of the
 *                               {@link sh.vork.ai.agent.AgentTemplate} to switch to;
 *                               {@code null} when {@code FINISHED_TURN} or {@code CONTINUE_TURN}
 * @param delegationInstructions self-contained task parameters for the target agent;
 *                               {@code null} when {@code FINISHED_TURN} or {@code CONTINUE_TURN}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredAgentResponse(
        String status,
        String textResponse,
        String targetAgent,
        String delegationInstructions
) {}
