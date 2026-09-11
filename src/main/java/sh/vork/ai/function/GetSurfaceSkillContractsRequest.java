package sh.vork.ai.function;

/**
 * Input schema for the {@code getSurfaceSkillContracts} tool.
 */
public record GetSurfaceSkillContractsRequest(
        String surfaceUuid
) {
}
