package sh.vork.ai.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import sh.vork.security.SecureCredentialStore;

/**
 * Substitutes {@code {{SECRET_NAME}}} tokens in tool-call argument strings with
 * the real secret values stored in {@link SecureCredentialStore}.
 *
 * <p>Only {@code UPPER_SNAKE_CASE} identifiers inside double curly braces are
 * treated as secret tokens (pattern: {@code \{\{[A-Z][A-Z0-9_]*\}\}}).
 * Tokens that start with a lower-case letter are left unchanged so that
 * template expressions used in other contexts are not inadvertently consumed.
 *
 * <p>If a token is found but no matching secret exists for the user, the token
 * is left as-is and a {@code WARN} is emitted — the tool will receive the literal
 * placeholder string and will likely return an auth error, which the AI can relay
 * to the user.
 *
 * <p>The resolved secret values are <strong>never</strong> logged.
 */
@Component
public class SkillSecretSubstitutor {

    private static final Logger log = LoggerFactory.getLogger(SkillSecretSubstitutor.class);

    /** Matches {{UPPER_SNAKE_CASE}} tokens only. */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\\{\\{([A-Z][A-Z0-9_]*)\\}\\}");

    private final SecureCredentialStore secretStore;

    public SkillSecretSubstitutor(SecureCredentialStore secretStore) {
        this.secretStore = secretStore;
    }

    /**
     * Replaces all {@code {{KEY}}} tokens in {@code arguments} with the
     * corresponding secret values for {@code username}.
     *
     * @param arguments raw tool-call JSON arguments string; may be {@code null}
     * @param username  the authenticated user whose secrets to look up
     * @return arguments with tokens replaced, or the original string unchanged
     *         when no tokens are present or {@code arguments} is {@code null}
     */
    public String substitute(String arguments, String username) {
        if (arguments == null || !arguments.contains("{{") || username == null || username.isBlank()) {
            return arguments;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(arguments);
        if (!matcher.find()) {
            return arguments;
        }

        // Reset and do the replacement pass
        matcher.reset();
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = secretStore.getSecretForUser(username, key);
            if (value != null) {
                log.debug("Secret token substituted in tool arguments [key={}, user={}]", key, username);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else {
                log.warn("Secret token {{{}}} not found in store for user {} — leaving token unchanged", key, username);
                matcher.appendReplacement(sb, Matcher.quoteReplacement("{{" + key + "}}"));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
