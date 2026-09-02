package sh.vork.binding.contract;

import sh.vork.artifact.ArtifactStatus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import sh.vork.orm.DatabaseEntity;

/**
 * Binding contract artifact that groups tool definitions under deterministic VID identity.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BindingContract(
        String uuid,
        String name,
        String description,
        List<BindingContractToolDefinition> tools,
        String groupId,
        String artifactId,
        String version,
        ArtifactStatus artifactStatus,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public BindingContract {
        if (name == null) {
            name = "";
        }
        if (description == null) {
            description = "";
        }
        if (tools == null) {
            tools = List.of();
        }
        if (groupId == null || groupId.isBlank()) {
            groupId = "binding";
        }
        if (artifactId == null || artifactId.isBlank()) {
            artifactId = "contract";
        }
        if (version == null || version.isBlank()) {
            version = "SNAPSHOT";
        }
        artifactStatus = artifactStatus == null ? ArtifactStatus.SNAPSHOT : artifactStatus;
    }

    public boolean isSnapshotMutable() {
        return artifactStatus == ArtifactStatus.SNAPSHOT || artifactStatus == ArtifactStatus.REJECTED;
    }
}
