package sh.vork.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkillLegacyDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void legacyJson_withoutVisibility_defaultsToPublic_andIgnoresAutoShareField() throws Exception {
        String legacyJson = """
                {
                  \"uuid\": \"skill-legacy-1\",
                  \"name\": \"Legacy Skill\",
                  \"description\": \"Older stored payload\",
                  \"groupUuid\": \"group-1\",
                  \"autoShareWithinGroup\": true,
                  \"forceUserInput\": false,
                  \"parameters\": [],
                  \"instructions\": \"run\",
                  \"allowedTools\": [],
                  \"allowedTypes\": [],
                  \"subSkillUuids\": [],
                  \"createdBySessionCount\": 1,
                  \"createdAt\": 1,
                  \"updatedAt\": 1,
                  \"secrets\": []
                }
                """;

        Skill skill = objectMapper.readValue(legacyJson, Skill.class);

        assertNotNull(skill);
        assertEquals("skill-legacy-1", skill.uuid());
        assertEquals(SkillVisibility.PUBLIC, skill.visibility());
    }
}
