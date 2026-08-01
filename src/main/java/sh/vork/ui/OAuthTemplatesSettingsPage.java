package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class OAuthTemplatesSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-puzzle-piece";
    }

    @Override
    public String getName() {
        return "OAuth Templates";
    }

    @Override
    public String getDescription() {
        return "Manage shared OAuth provider defaults and authorization metadata.";
    }

    @Override
    public String getPath() {
        return "oauth-templates";
    }
}
