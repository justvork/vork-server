package sh.vork.skill;

/**
 * Declares how each skill parameter should be sourced and validated at runtime.
 */
public enum SkillParameterInputMode {
    USER_ALWAYS_PROMPT,
    USER_PROMPT_IF_EMPTY,
    AI_REQUIRED,
    AI_OPTIONAL
}
