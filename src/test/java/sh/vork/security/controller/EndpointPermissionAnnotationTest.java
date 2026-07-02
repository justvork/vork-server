package sh.vork.security.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import sh.vork.ai.controller.AgentController;
import sh.vork.skill.SkillController;
import sh.vork.typegen.controller.TypeDatabaseController;

class EndpointPermissionAnnotationTest {

    @Test
    void agentWriteEndpoints_requireAgentsWritePermission() throws Exception {
        Class<?> agentRequestClass = classByName("sh.vork.ai.controller.AgentController$AgentRequest");
        assertPreAuthorize(AgentController.class, "createAgent", "hasAuthority('AGENTS_WRITE')", agentRequestClass);
        assertPreAuthorize(AgentController.class, "updateAgent", "hasAuthority('AGENTS_WRITE')", String.class, agentRequestClass);
        assertPreAuthorize(AgentController.class, "deleteAgent", "hasAuthority('AGENTS_WRITE')", String.class);
    }

    @Test
    void skillWriteEndpoints_requireSkillsWritePermission() throws Exception {
        assertPreAuthorize(SkillController.class, "createSkill", "hasAuthority('SKILLS_WRITE')", classByName("sh.vork.skill.SkillService$SkillRequest"));
        assertPreAuthorize(SkillController.class, "updateSkill", "hasAuthority('SKILLS_WRITE')", String.class, classByName("sh.vork.skill.SkillService$SkillRequest"));
        assertPreAuthorize(SkillController.class, "deleteSkill", "hasAuthority('SKILLS_WRITE')", String.class);
        assertPreAuthorize(SkillController.class, "createGroup", "hasAuthority('SKILLS_WRITE')", classByName("sh.vork.skill.SkillService$SkillGroupRequest"));
        assertPreAuthorize(SkillController.class, "updateGroup", "hasAuthority('SKILLS_WRITE')", String.class, classByName("sh.vork.skill.SkillService$SkillGroupRequest"));
        assertPreAuthorize(SkillController.class, "deleteGroup", "hasAuthority('SKILLS_WRITE')", String.class);
        assertPreAuthorize(SkillController.class, "importGroup", "hasAuthority('SKILLS_WRITE')", classByName("sh.vork.skill.SkillService$SkillGroupExportPackage"));
    }

    @Test
    void typeWriteEndpoints_requireTypesWritePermission() throws Exception {
        assertPreAuthorize(TypeDatabaseController.class, "save", "hasAuthority('TYPES_WRITE')", String.class, jakarta.servlet.http.HttpServletRequest.class);
        assertPreAuthorize(TypeDatabaseController.class, "delete", "hasAuthority('TYPES_WRITE')", String.class, String.class);
    }

    private static void assertPreAuthorize(Class<?> clazz, String methodName, String expected, Class<?>... args) throws Exception {
        Method method = clazz.getDeclaredMethod(methodName, args);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertEquals(expected, annotation == null ? null : annotation.value(),
                () -> clazz.getSimpleName() + "." + methodName + " should be annotated with " + expected);
    }

    private static Class<?> classByName(String fqn) throws ClassNotFoundException {
        return Class.forName(fqn);
    }
}
