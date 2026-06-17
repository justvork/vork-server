package sh.vork.skill;

/**
 * A named secret required by a {@link Skill}.
 *
 * <p>The {@code name} is the key used both for storage in
 * {@link sh.vork.security.SecureCredentialStore} and as the substitution
 * token in tool arguments.  When the AI needs to pass this secret as a
 * value (e.g. in an HTTP header), it uses the literal string
 * {@code {{NAME}}} and the secret substitution layer replaces it with the
 * real value before the tool executes.
 *
 * @param name        UPPER_SNAKE_CASE identifier, e.g. {@code XERO_API_KEY}.
 *                    Must be unique within the skill.
 * @param description Human-readable description shown to the user when they
 *                    are prompted to supply the secret, e.g.
 *                    {@code "Xero OAuth 2.0 client secret"}.
 */
public record SkillSecret(
        String name,
        String description
) {
    public SkillSecret {
        if (name == null || name.isBlank()) name = "SECRET";
        if (description == null)            description = "";
    }
}
