package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class DataInspectorSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-table-list";
    }

    @Override
    public String getName() {
        return "Data Inspector";
    }

    @Override
    public String getDescription() {
        return "Inspect and explore persisted records across types.";
    }

    @Override
    public String getPath() {
        return "data-inspector";
    }
}
