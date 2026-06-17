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
 *   <li>{@code "DELEGATE_TURN"} — the agent wants to hand off work to a
 *       specialist sub-agent.  The orchestrator resolves {@code targetAgent} by name,
 *       updates the session's {@code activeAgentTemplateId}, broadcasts an
 *       {@code AGENT_TRANSITION} event, and starts a new generation pass using
 *       {@code delegationInstructions} as the prompt.  Only available to orchestrator
 *       agents (e.g. Concierge).</li>
 *   <li>{@code "SWITCH_AGENT"} — the agent wants to change the session's active
 *       agent without immediately starting a new generation pass.  Use this when
 *       the user explicitly asks to switch agents (e.g., "go back to Concierge").
 *       The orchestrator resolves {@code targetAgent} by name, saves the new
 *       {@code activeAgentTemplateId}, broadcasts an {@code AGENT_TRANSITION}
 *       notification and an {@code AGENT_SWITCH} event to update the UI, then
 *       returns the agent's {@code textResponse} as the final reply for the
 *       current turn.  Only available to interactive sessions.</li>
 * </ul>
 *
 * @param status                 {@code "FINISHED_TURN"}, {@code "DELEGATE_TURN"}, or {@code "SWITCH_AGENT"}
 * @param textResponse           the agent's human-readable result or message; always
 *                               populate this — it is surfaced directly to the user
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
