package sh.vork.ai.security;

import java.util.function.Function;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorator that adds UI formatting capability to a tool callback.
 */
public class VisualizableToolCallback implements ToolCallback, VisualizableTool {

    private final ToolCallback delegate;
    private final Function<String, String> formatter;

    public VisualizableToolCallback(ToolCallback delegate, Function<String, String> formatter) {
        this.delegate = delegate;
        this.formatter = formatter;
    }

    @Override
    public String call(String arguments) {
        return delegate.call(arguments);
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        return delegate.call(arguments, toolContext);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String formatAuthorizationDetails(String argumentsJson) {
        return formatter.apply(argumentsJson);
    }
}
