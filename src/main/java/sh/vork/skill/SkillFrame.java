package sh.vork.skill;

import java.util.List;
import java.util.Map;

/**
 * A single frame pushed onto {@link sh.vork.ai.entity.AiSession#skillStack()} when
 * a skill begins executing inside the parent session.
 *
 * <p>The frame carries everything {@link sh.vork.ai.service.AiOrchestrationService}
 * needs to restrict the tool set and override the system prompt for the duration of
 * the skill execution.  It is popped from the stack by {@code completeSkillExecution}.
 */
public record SkillFrame(
        String skillUuid,
        String skillName,
        String instructions,
        String outputTemplate,
        List<String> allowedTools,
        List<String> allowedTypes,
        Map<String, String> params,
        /**
         * Number of messages in the parent session at the moment this skill frame was pushed.
         * Used to slice the session history so the skill AI only sees messages from its own
         * execution, not the full background-job history.  {@code null} for frames created
         * before this field was introduced (treated as "use empty history").
         */
        Integer startMessageCount
) {}
