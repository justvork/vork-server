package sh.vork.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionInputParameter;
import sh.vork.reflection.ReflectionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider adapter that exposes current reflection bindings via the generic provider contract.
 */
@Service
public class ReflectionBindingProvider implements BindingProvider {

    private static final String PROVIDER_ID = "reflection";

    private final ReflectionService reflectionService;
    private final ObjectMapper objectMapper;

    public ReflectionBindingProvider(ReflectionService reflectionService, ObjectMapper objectMapper) {
        this.reflectionService = reflectionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<BindingSummary> listBindings() {
        List<BindingSummary> result = new ArrayList<>();
        for (ReflectionGroup group : reflectionService.listGroups()) {
            for (ReflectionBinding binding : reflectionService.bindingsForGroup(group.uuid())) {
                String groupName = group.name() == null ? group.uuid() : group.name();
                String bindingName = binding.name() == null ? binding.uuid() : binding.name();
                String label = groupName + " (" + bindingName + ")";
                result.add(new BindingSummary(
                        binding.uuid(),
                        label,
                        PROVIDER_ID,
                        List.of(bindingName),
                        "Reflection binding profile for group " + groupName));
            }
        }
        return result;
    }

    @Override
    public List<BindingOperationContract> listOperationContracts(String bindingId, String profile) {
        ReflectionBinding binding = reflectionService.getBindingByUuid(bindingId);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown reflection binding: " + bindingId);
        }
        if (profile != null && !profile.isBlank() && !binding.name().equalsIgnoreCase(profile.trim())) {
            throw new IllegalArgumentException("Unknown profile for binding: " + profile);
        }

        ReflectionGroup group = reflectionService.getGroup(binding.groupUuid());
        List<BindingOperationContract> contracts = new ArrayList<>();
        for (Reflection reflection : reflectionService.reflectionsForGroup(binding.groupUuid())) {
            contracts.add(new BindingOperationContract(
                    reflection.id(),
                    reflection.name(),
                    reflection.description(),
                    buildReflectionInputSchema(reflection.inputParameters()),
                    parseOutputSchema(reflection.outputSchema()),
                    reflection.responseContentType()));
        }
        if (group != null) {
            contracts.sort((a, b) -> {
                String left = a.name() == null ? a.operationId() : a.name();
                String right = b.name() == null ? b.operationId() : b.name();
                return left.compareToIgnoreCase(right);
            });
        }
        return contracts;
    }

    @Override
    public BindingInvocationResult invoke(BindingInvocationRequest request) {
        ReflectionBinding binding = reflectionService.getBindingByUuid(request.bindingId());
        if (binding == null) {
            throw new IllegalArgumentException("Unknown reflection binding: " + request.bindingId());
        }

        String profile = request.profile();
        if (profile != null && !profile.isBlank() && !binding.name().equalsIgnoreCase(profile.trim())) {
            throw new IllegalArgumentException("Unknown profile for binding: " + profile);
        }

        String actor = request.actor() == null || request.actor().isBlank() ? "system" : request.actor().trim();
        String raw = reflectionService.executeRestReflection(
                request.operationId(),
                request.args() == null ? Map.of() : request.args(),
                binding.name(),
                actor);

        try {
            JsonNode node = objectMapper.readTree(raw);
            int statusCode = node.path("statusCode").isNumber() ? node.path("statusCode").asInt() : 200;
            String contentType = node.path("contentType").isTextual()
                    ? node.path("contentType").asText()
                    : "application/json";
            return new BindingInvocationResult(statusCode, node, contentType);
        } catch (Exception ignored) {
            return new BindingInvocationResult(200, raw, "application/json");
        }
    }

    private static Map<String, Object> buildReflectionInputSchema(List<ReflectionInputParameter> params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ReflectionInputParameter param : params == null ? List.<ReflectionInputParameter>of() : params) {
            String name = param.name();
            String type = normalizeSchemaType(param.type());
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", type);
            if (param.description() != null && !param.description().isBlank()) {
                schema.put("description", param.description());
            }
            properties.put(name, schema);
            if (param.required()) {
                required.add(name);
            }
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        input.put("properties", properties);
        input.put("required", required);
        return input;
    }

    private Object parseOutputSchema(String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readTree(outputSchema);
        } catch (Exception ignored) {
            return Map.of("type", "string", "rawSchema", outputSchema);
        }
    }

    private static String normalizeSchemaType(String value) {
        String type = value == null ? "string" : value.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "int", "integer", "long" -> "integer";
            case "double", "float", "number", "decimal" -> "number";
            case "bool", "boolean" -> "boolean";
            case "object", "array", "string" -> type;
            default -> "string";
        };
    }
}
