package sh.vork.surface.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionInputParameter;
import sh.vork.reflection.ReflectionService;
import sh.vork.surface.Surface;

import java.util.ArrayList;
import java.util.List;
import sh.vork.util.ToolIdGenerator;

/**
 * Produces safe reflection contracts for Surface runtime and Surface Developer tooling.
 */
@Service
public class SurfaceReflectionContractService {

    private final DatabaseRepository<Surface> surfaceRepository;
    private final ReflectionService reflectionService;
    private final ObjectMapper objectMapper;

    public SurfaceReflectionContractService(DatabaseRepository<Surface> surfaceRepository,
                                           ReflectionService reflectionService,
                                           ObjectMapper objectMapper) {
        this.surfaceRepository = surfaceRepository;
        this.reflectionService = reflectionService;
        this.objectMapper = objectMapper;
    }

    public Surface getSurface(String surfaceUuid) {
        return surfaceRepository.get(surfaceUuid);
    }

    public Surface getSurfaceByToolId(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        String normalized = ToolIdGenerator.normalizeBase(toolId, "surface");
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(surface -> normalized.equals(ToolIdGenerator.normalizeBase(surface.toolId(), "surface")))
                    .findFirst()
                    .orElse(null);
        }
    }

    public Surface resolveSurfaceByUuidOrToolId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        Surface byUuid = surfaceRepository.get(identifier);
        return byUuid != null ? byUuid : getSurfaceByToolId(identifier);
    }

    public Surface findSurfaceBySessionUuid(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return null;
        }
        try (var stream = surfaceRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(surface -> sessionUuid.equals(surface.sessionUuid()))
                    .findFirst()
                    .orElse(null);
        }
    }

    public SurfaceReflectionContractsResponse contractsForSurface(String surfaceIdentifier,
                                                                  String onlyBindingGroupToolId,
                                                                  String onlyBindingProfileName) {
        Surface surface = resolveSurfaceByUuidOrToolId(surfaceIdentifier);
        if (surface == null) {
            throw new IllegalArgumentException("Surface not found: " + surfaceIdentifier);
        }

        List<BindingContract> bindingContracts = new ArrayList<>();
        for (String bindingUuid : surface.reflectionBindingUuids()) {
            ReflectionBinding binding = reflectionService.getBindingByUuid(bindingUuid);
            if (binding == null) {
                continue;
            }
            ReflectionGroup group = reflectionService.getGroup(binding.groupUuid());
            if (group == null) {
                continue;
            }
            if (onlyBindingGroupToolId != null && !onlyBindingGroupToolId.isBlank()) {
                String groupToolId = ToolIdGenerator.normalizeBase(group.toolId(), "group");
                String required = ToolIdGenerator.normalizeBase(onlyBindingGroupToolId, "group");
                if (!required.equals(groupToolId)) {
                    continue;
                }
            }
            if (onlyBindingProfileName != null
                    && !onlyBindingProfileName.isBlank()
                    && !onlyBindingProfileName.trim().equalsIgnoreCase(binding.name())) {
                continue;
            }

            List<ReflectionContract> reflectionContracts = new ArrayList<>();
            for (Reflection reflection : reflectionService.reflectionsForGroup(group.uuid())) {
                reflectionContracts.add(toReflectionContract(reflection, group, binding));
            }

            bindingContracts.add(new BindingContract(
                    group.toolId(),
                    binding.name(),
                    group.name(),
                    reflectionContracts));
        }

        return new SurfaceReflectionContractsResponse(
                surface.toolId(),
                surface.name(),
                bindingContracts);
    }

    private ReflectionContract toReflectionContract(Reflection reflection,
                                                    ReflectionGroup group,
                                                    ReflectionBinding binding) {
        JsonNode parsedSchema = null;
        boolean outputSchemaValid = true;
        String outputSchemaText = "";

        if (reflection.outputSchema() != null && !reflection.outputSchema().isBlank()) {
            outputSchemaText = reflection.outputSchema();
            try {
                parsedSchema = objectMapper.readTree(reflection.outputSchema());
            } catch (Exception ex) {
                outputSchemaValid = false;
            }
        }

        return new ReflectionContract(
                reflection.id(),
                group == null ? "" : group.toolId(),
                binding == null ? "default" : binding.name(),
                reflection.name(),
                reflection.description(),
                reflection.inputParameters() == null ? List.of() : List.copyOf(reflection.inputParameters()),
                reflection.method(),
                reflection.responseContentType(),
                parsedSchema,
                outputSchemaValid,
                outputSchemaText
        );
    }

    public record SurfaceReflectionContractsResponse(
            String surfaceId,
            String surfaceName,
            List<BindingContract> bindings
    ) {}

    public record BindingContract(
            String bindingId,
            String bindingProfile,
            String groupName,
            List<ReflectionContract> reflections
    ) {}

    public record ReflectionContract(
            String reflectionId,
            String bindingId,
            String bindingProfile,
            String name,
            String description,
            List<ReflectionInputParameter> inputParameters,
            String method,
            String responseContentType,
            JsonNode outputSchema,
            boolean outputSchemaValid,
            String outputSchemaText
    ) {}
}
