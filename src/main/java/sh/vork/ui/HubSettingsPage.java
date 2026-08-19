package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class HubSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-compass";
    }

    @Override
    public String getName() {
        return "Hub";
    }

    @Override
    public String getDescription() {
        return "Browse and install repository artifacts from the Hub.";
    }

    @Override
    public String getPath() {
        return "hub";
    }
}
