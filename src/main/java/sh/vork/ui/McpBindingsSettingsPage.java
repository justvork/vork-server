package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class McpBindingsSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-plug-circle-bolt";
    }

    @Override
    public String getName() {
        return "MCP Bindings";
    }

    @Override
    public String getDescription() {
        return "Manage MCP server bindings, sync contracts, and activation state.";
    }

    @Override
    public String getPath() {
        return "mcp-bindings";
    }
}
