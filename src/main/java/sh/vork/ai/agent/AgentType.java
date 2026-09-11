package sh.vork.ai.agent;

/**
 * Classifies the operational context an {@link AgentTemplate} is designed for.
 *
 * <ul>
 *   <li>{@link #INTERACTIVE} — conversational agents shown in the chat agent picker.
 *       These agents are designed to respond to user turns, delegate between specialist
 *       personas, and drive real-time interactions.</li>
 *   <li>{@link #BACKGROUND} — automation agents used exclusively by scheduled jobs and
 *       background pipelines.  These agents must call {@code completeBackgroundTask} to
 *       finalise a run and must never appear in the interactive chat dropdown.</li>
 *   <li>{@link #SURFACE} — agents designed to be invoked from surfaces.
 *       These agents are attachable to individual surfaces and are not part of
 *       the interactive chat picker by default.</li>
 * </ul>
 */
public enum AgentType {
    INTERACTIVE,
    BACKGROUND,
    SURFACE
}
