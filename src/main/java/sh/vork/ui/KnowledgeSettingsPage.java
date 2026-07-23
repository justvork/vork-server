package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class KnowledgeSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-brain";
    }

    @Override
    public String getName() {
        return "Knowledge Base";
    }

    @Override
    public String getDescription() {
        return "Manage persistent organizational knowledge articles.";
    }

    @Override
    public String getPath() {
        return "knowledge";
    }
}
