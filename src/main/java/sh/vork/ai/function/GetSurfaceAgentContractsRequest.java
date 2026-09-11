package sh.vork.ai.function;

/**
 * Input schema for the {@code getSurfaceAgentContracts} tool.
 */
public record GetSurfaceAgentContractsRequest(
        String surfaceUuid
) {
}
