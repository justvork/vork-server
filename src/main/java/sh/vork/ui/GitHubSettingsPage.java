package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class GitHubSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-link";
    }

    @Override
    public String getName() {
        return "GitHub";
    }

    @Override
    public String getDescription() {
        return "Connect or disconnect your GitHub account for contribution workflows.";
    }

    @Override
    public String getPath() {
        return "github";
    }
}
