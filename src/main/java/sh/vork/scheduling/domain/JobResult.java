package sh.vork.scheduling.domain;

import sh.vork.orm.DatabaseEntity;

/**
 * Immutable record of a background job's completion outcome.
 *
 * <p>Saved by the {@code completeBackgroundTask} tool when the AI agent signals
 * that its background session has finished.  One record is written per job run.
 */
public record JobResult(
        String uuid,           // random UUID — primary key
        String jobId,          // ScheduledJob.id that produced this result
        String sessionUuid,    // tracking session UUID for the run
        boolean success,       // true = objectives met; false = failed / partial
        String report,         // agent-authored summary of what was done and produced
        long completedAt       // epoch milliseconds when completeBackgroundTask was called
) implements DatabaseEntity {}
