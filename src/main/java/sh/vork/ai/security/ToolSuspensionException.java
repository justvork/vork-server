package sh.vork.ai.security;

/**
 * Control-flow exception used to freeze a chat turn before executing a
 * restricted tool callback.
 */
public class ToolSuspensionException extends RuntimeException {

    private final String toolName;
    private final String arguments;
    private final String reasoning;

    public ToolSuspensionException(String toolName, String arguments) {
        this(toolName, arguments, null);
    }

    public ToolSuspensionException(String toolName, String arguments, String reasoning) {
        super("Tool execution suspended pending authorization: " + toolName);
        this.toolName = toolName;
        this.arguments = arguments;
        this.reasoning = reasoning;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public String getReasoning() {
        return reasoning;
    }
}
