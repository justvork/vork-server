package sh.vork.skill;

/**
 * Thrown by {@link SkillService#executeSkill} to break out of the Spring AI
 * tool-call chain and signal to {@code ChatService.executeAgentLoop} that a
 * skill context frame has been pushed onto the session stack and the skill
 * sub-loop should now start.
 *
 * <p>This exception is intentionally NOT handled by Spring AI — it propagates
 * through {@code safeGenerateWithHistory} → {@code executeAgentLoop}, where
 * it is caught and processed.
 */
public class SkillActivatedException extends RuntimeException {

    private final String skillUuid;
    private final String skillName;
    private final String initialPrompt;

    public SkillActivatedException(String skillUuid, String skillName, String initialPrompt) {
        super("Skill activated: " + skillName);
        this.skillUuid     = skillUuid;
        this.skillName     = skillName;
        this.initialPrompt = initialPrompt;
    }

    public String getSkillUuid()     { return skillUuid; }
    public String getSkillName()     { return skillName; }
    public String getInitialPrompt() { return initialPrompt; }
}
