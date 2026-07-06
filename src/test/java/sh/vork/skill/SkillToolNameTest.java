package sh.vork.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolNameTest {

    @Test
    void toolName_includesGroupSegment_toAvoidCrossGroupCollisions() {
        Skill connectCalendar = new Skill(
                "skill-1",
                "Connect",
                "Connect to calendar",
                "googleCalendarViewer",
                SkillVisibility.PUBLIC,
                List.of(),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                1L,
                1L,
                List.of());

        Skill connectMail = new Skill(
                "skill-2",
                "Connect",
                "Connect to mail",
                "gmailClient",
                SkillVisibility.PUBLIC,
                List.of(),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                1L,
                1L,
                List.of());

        assertNotEquals(connectCalendar.toolName(), connectMail.toolName());
        assertEquals("googleCalendarViewer_connect", connectCalendar.toolName());
        assertEquals("gmailClient_connect", connectMail.toolName());
    }

    @Test
    void toolName_isSafeForProviderFunctionNaming() {
        Skill skill = new Skill(
                "12345678-1234-1234-1234-123456789abc",
                "Viewer/Connect & Sync",
                "desc",
                "Google Calendar: Viewer",
                SkillVisibility.PUBLIC,
                List.of(),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                1L,
                1L,
                List.of());

        String name = skill.toolName();
        assertTrue(name.matches("[A-Za-z0-9_]+"));
        assertTrue(name.length() <= 64);
    }
}
