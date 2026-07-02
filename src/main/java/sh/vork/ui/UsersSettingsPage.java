package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class UsersSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-users";
    }

    @Override
    public String getName() {
        return "Users";
    }

    @Override
    public String getDescription() {
        return "Create users, assign roles, and manage account access.";
    }

    @Override
    public String getPath() {
        return "users";
    }
}
