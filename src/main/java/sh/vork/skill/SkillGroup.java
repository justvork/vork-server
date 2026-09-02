package sh.vork.skill;

import sh.vork.artifact.ArtifactStatus;

import sh.vork.orm.DatabaseEntity;
import sh.vork.typegen.ExportableType;

import java.util.List;

/**
 * A parent container for related skills.
 *
 * <p>Groups own category/author metadata and embedded member skills, allowing
 * related skills (for example connect/read/send variants) to be managed and
 * exported/imported together.
 */
@ExportableType(description = "Skill group container")
public record SkillGroup(
        String       uuid,
        String       name,
        String       author,
        String       category,
        List<Skill>  skills,
        String       groupId,
        String       artifactId,
        String       version,
        ArtifactStatus artifactStatus,
        long         createdAt,
        long         updatedAt
) implements DatabaseEntity {

    public SkillGroup {
        if (name == null || name.isBlank()) name = "Unnamed Group";
        if (author == null) author = "";
        if (category == null) category = "";
        if (skills == null) skills = List.of();
        if (groupId == null || groupId.isBlank()) groupId = "legacy";
        if (artifactId == null || artifactId.isBlank()) artifactId = "skillgroup";
        if (version == null || version.isBlank()) {
            version = "SNAPSHOT";
        } else {
            version = version.trim();
            if (version.matches("^[0-9]+$")) {
                version = "SNAPSHOT";
            }
        }
        artifactStatus = artifactStatus == null ? ArtifactStatus.SNAPSHOT : artifactStatus;
    }

    /**
     * VID-compatible alias retained for clients expecting artifactVersion.
     */
    public String artifactVersion() {
        return version;
    }

    public SkillGroup(String uuid,
                      String name,
                      String author,
                      String category,
                      List<Skill> skills,
                      long ignoredLegacyVersion,
                      long createdAt,
                      long updatedAt) {
        this(uuid, name, author, category, skills, "legacy", "skillgroup", "SNAPSHOT", ArtifactStatus.SNAPSHOT, createdAt, updatedAt);
    }
}
