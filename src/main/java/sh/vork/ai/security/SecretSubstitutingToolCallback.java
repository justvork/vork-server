package sh.vork.ai.security;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A {@link ToolCallback} decorator that substitutes {@code {{SECRET_NAME}}}
 * tokens in the tool's input arguments before delegating to the real callback.
 *
 * <p>This wrapper is applied to every tool callback registered on the
 * {@link org.springframework.ai.chat.client.ChatClient} so that any tool the
 * AI invokes — {@code httpRequest}, SSH tools, custom schema tools, etc. —
 * will have secret placeholders resolved transparently before execution.
 *
 * <p>The fast-path check ({@code arguments.contains("{{")} ) means there is
 * negligible overhead when no secret tokens are present, which is the common
 * case for most tool invocations.
 */
public class SecretSubstitutingToolCallback implements ToolCallback {

    private final ToolCallback          delegate;
    private final SkillSecretSubstitutor substitutor;
    private final String                username;

    public SecretSubstitutingToolCallback(ToolCallback delegate,
                                          SkillSecretSubstitutor substitutor,
                                          String username) {
        this.delegate    = delegate;
        this.substitutor = substitutor;
        this.username    = username;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(substitutor.substitute(toolInput, username));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(substitutor.substitute(toolInput, username), toolContext);
    }
}
