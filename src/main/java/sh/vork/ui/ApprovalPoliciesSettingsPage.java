package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class ApprovalPoliciesSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-user-check";
    }

    @Override
    public String getName() {
        return "Approval Policies";
    }

    @Override
    public String getDescription() {
        return "Configure default approval routing and conditional overrides.";
    }

    @Override
    public String getPath() {
        return "approval-policies";
    }
}
