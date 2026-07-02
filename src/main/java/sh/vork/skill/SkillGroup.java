package sh.vork.skill;

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
        long         version,
        long         createdAt,
        long         updatedAt
) implements DatabaseEntity {

    public SkillGroup {
        if (name == null || name.isBlank()) name = "Unnamed Group";
        if (author == null) author = "";
        if (category == null) category = "";
        if (skills == null) skills = List.of();
        if (version < 1) version = 1;
    }
}
