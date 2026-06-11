package sh.vork.ai.entity;

public enum SessionOriginMode {
    WEB,
    BACKGROUND,
    TELEGRAM,
    SLACK,
    /** Sandboxed skill execution — resumable via pending-sessions UI. */
    SKILL
}
