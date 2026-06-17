package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code think} meta-tool.
 *
 * <p>The AI calls this to log reasoning or analysis mid-turn without ending
 * the turn.  The reasoning is broadcast as an {@code AI_THINKING} WebSocket
 * event so interactive UIs can display it, and is logged at DEBUG level.
 * The tool always returns immediately so the AI can continue to invoke the
 * next action tool in the same turn.
 */
public record ThinkRequest(
        @JsonProperty(required = true, value = "reasoning")
        @JsonPropertyDescription("Your internal reasoning, analysis, or step-by-step plan before calling the next tool. Be concise.")
        String reasoning
) {}
