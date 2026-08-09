package sh.vork.binding;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates all registered binding providers into a single catalog.
 */
@Service
public class BindingCatalogService {

    private final List<BindingProvider> providers;

    public BindingCatalogService(List<BindingProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public List<BindingSummary> listBindings() {
        Map<String, String> ownerByBindingId = new LinkedHashMap<>();
        List<BindingSummary> all = new ArrayList<>();

        for (BindingProvider provider : providers) {
            List<BindingSummary> providerBindings = provider.listBindings();
            for (BindingSummary binding : providerBindings) {
                if (binding == null || binding.bindingId() == null || binding.bindingId().isBlank()) {
                    continue;
                }
                String owner = ownerByBindingId.putIfAbsent(binding.bindingId(), provider.providerId());
                if (owner != null && !owner.equals(provider.providerId())) {
                    throw new IllegalStateException("Duplicate bindingId across providers: "
                            + binding.bindingId() + " (" + owner + " vs " + provider.providerId() + ")");
                }
                all.add(binding);
            }
        }

        all.sort(Comparator.comparing(BindingSummary::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(BindingSummary::bindingId));
        return all;
    }

    public List<BindingOperationContract> listOperationContracts(String bindingId, String profile) {
        BindingProvider provider = resolveProvider(bindingId);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown binding: " + bindingId);
        }
        return provider.listOperationContracts(bindingId, profile);
    }

    public BindingInvocationResult invoke(BindingInvocationRequest request) {
        if (request == null || request.bindingId() == null || request.bindingId().isBlank()) {
            throw new IllegalArgumentException("bindingId is required");
        }
        BindingProvider provider = resolveProvider(request.bindingId());
        if (provider == null) {
            throw new IllegalArgumentException("Unknown binding: " + request.bindingId());
        }
        return provider.invoke(request);
    }

    private BindingProvider resolveProvider(String bindingId) {
        String target = bindingId == null ? "" : bindingId.toLowerCase(Locale.ROOT);
        for (BindingProvider provider : providers) {
            boolean matches = provider.listBindings().stream()
                    .anyMatch(binding -> binding != null
                            && binding.bindingId() != null
                            && binding.bindingId().equalsIgnoreCase(target));
            if (matches) {
                return provider;
            }
        }
        return null;
    }
}
