package sh.vork.scheduling.domain;

/** How a scheduled job is triggered. */
public enum InvocationType {
    /** Never runs automatically — only via an explicit "Run Now" action. */
    MANUAL,
    /** Internal one-off delegated run that executes immediately and is not scheduler-driven. */
    DYNAMIC,
    /** Runs once at {@code startTime} then transitions to COMPLETED. */
    ONE_TIME,
    /** Runs at {@code startTime} then repeats every {@code repeatDuration} interval. */
    REPEAT
}
