package sh.vork.binding;

import java.util.List;

/**
 * Extension point for capability sources that publish Bindings.
 */
public interface BindingProvider {

    String providerId();

    List<BindingSummary> listBindings();

    List<BindingOperationContract> listOperationContracts(String bindingId, String profile);

    BindingInvocationResult invoke(BindingInvocationRequest request);
}
