package sh.vork.ui;

import org.springframework.stereotype.Component;

/**
 * Secrets Manager settings page navigation bean.
 * Registers the secrets page in the settings home grid.
 */
@Component
public class SecretsSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-lock";
    }

    @Override
    public String getName() {
        return "Secrets Manager";
    }

    @Override
    public String getDescription() {
        return "View and manage your encrypted secrets.";
    }

    @Override
    public String getPath() {
        return "secrets";
    }
}
