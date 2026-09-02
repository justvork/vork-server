package sh.vork.binding.contract;

import java.util.List;
import sh.vork.reflection.ReflectionInputParameter;

/**
 * Contract-level tool definition used to standardize tool IO across bindings.
 */
public record BindingContractToolDefinition(
        String name,
        String description,
        List<ReflectionInputParameter> inputParameters
) {

    public BindingContractToolDefinition {
        if (name == null) {
            name = "";
        }
        if (description == null) {
            description = "";
        }
        if (inputParameters == null) {
            inputParameters = List.of();
        }
    }
}
