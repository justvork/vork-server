package sh.vork.ai.security;

/**
 * Optional capability for tools that can provide pretty authorization details
 * for UI rendering.
 */
public interface VisualizableTool {

    /**
     * Formats raw tool arguments JSON into a clean markdown string for display.
     */
    String formatAuthorizationDetails(String argumentsJson);
}
