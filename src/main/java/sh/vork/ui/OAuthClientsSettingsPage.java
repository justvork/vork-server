package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class OAuthClientsSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-key";
    }

    @Override
    public String getName() {
        return "OAuth Clients";
    }

    @Override
    public String getDescription() {
        return "View configured OAuth clients and token status.";
    }

    @Override
    public String getPath() {
        return "oauth-clients";
    }
}
