package sh.vork.ui;

import org.springframework.stereotype.Component;

@Component
public class BindingContractsSettingsPage implements SettingsPage {

    @Override
    public String getIcon() {
        return "fa-file-contract";
    }

    @Override
    public String getName() {
        return "Binding Contracts";
    }

    @Override
    public String getDescription() {
        return "Manage reusable tool-definition contracts with deterministic artifact identity.";
    }

    @Override
    public String getPath() {
        return "binding-contracts";
    }
}
