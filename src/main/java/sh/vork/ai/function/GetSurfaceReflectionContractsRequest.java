package sh.vork.ai.function;

/**
 * Input schema for the {@code getSurfaceReflectionContracts} tool.
 */
public record GetSurfaceReflectionContractsRequest(
        String surfaceUuid,
        String bindingGroupToolId,
        String bindingProfileName
) {
}
