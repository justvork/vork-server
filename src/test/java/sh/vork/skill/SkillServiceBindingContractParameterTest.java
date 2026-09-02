package sh.vork.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.artifact.ArtifactStatus;
import sh.vork.binding.contract.BindingContract;
import sh.vork.binding.contract.BindingContractService;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.reflection.ReflectionAuthenticationMode;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionService;
import sh.vork.reflection.ReflectionType;

class SkillServiceBindingContractParameterTest {

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    void executeSkill_contractTypedParamAddsRuntimeBindingPermission() {
        String contractVid = "mail-email-SNAPSHOT";
        String bindingUuid = "binding-gmail";

        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        MapDatabaseRepository<AgentTemplate> agentRepo = new MapDatabaseRepository<>(AgentTemplate.class);

        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);
        ReflectionTestUtils.setField(service, "agentTemplateRepo", agentRepo);
        ReflectionTestUtils.setField(service, "typeGeneratorService", mock(sh.vork.typegen.TypeGeneratorService.class));

        BindingContractService contractService = mock(BindingContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ReflectionTestUtils.setField(service, "bindingContractService", contractService);
        ReflectionTestUtils.setField(service, "reflectionService", reflectionService);

        Skill skill = new Skill(
                "skill-mail",
                "Read Email",
                "desc",
                "grp-mail",
                SkillVisibility.PUBLIC,
                List.of(new SkillParameter("emailBinding", contractVid, "Email provider binding", SkillParameterInputMode.AI_REQUIRED)),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                List.of());
        skillRepo.save(skill);

        agentRepo.save(new AgentTemplate(
                "agent-1",
                "Mail Agent",
                "",
                List.of(),
                false,
                List.of("skill-mail"),
                AgentType.INTERACTIVE,
                List.of(bindingUuid)));

        String sessionUuid = "session-mail";
        sessionRepo.save(new AiSession(
                sessionUuid,
                "GEMINI",
                SessionOriginMode.WEB,
                "lee",
                "Mail Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(),
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of()));

        when(contractService.getContract(contractVid)).thenReturn(new BindingContract(
                contractVid,
                "Email Contract",
                "",
                List.of(),
                "mail",
                "email",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                System.currentTimeMillis(),
                System.currentTimeMillis()));
        when(reflectionService.getBindingByUuid(bindingUuid)).thenReturn(new ReflectionBinding(
                bindingUuid,
                "reflection-1",
                "gmail",
                "",
                Map.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis()));
        when(reflectionService.getBindingGroup(org.mockito.ArgumentMatchers.any())).thenReturn(new ReflectionGroup(
                "group-mail",
                "email",
                "Email",
                "",
                ReflectionType.REST,
                "",
                true,
                List.of(),
                List.of(),
                ReflectionAuthenticationMode.NONE,
                "",
                List.of(contractVid),
                "mail",
                "email",
                "SNAPSHOT",
                sh.vork.artifact.ArtifactStatus.SNAPSHOT,
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        ToolExecutionContext.bindSessionUuid(sessionUuid);
        assertThrows(SkillActivatedException.class,
                () -> service.executeSkill("skill-mail", Map.of("emailBinding", bindingUuid)));

        AiSession updated = sessionRepo.get(sessionUuid);
        assertEquals(1, updated.skillStack().size());
        SkillFrame frame = updated.skillStack().getFirst();
        assertTrue(frame.runtimeBindingUuids().contains(bindingUuid));
    }

    @Test
    void executeSkill_contractTypedParamRejectsBindingWithoutExpectedContract() {
        String expectedContractVid = "mail-email-SNAPSHOT";
        String wrongContractVid = "mail-calendar-SNAPSHOT";
        String bindingUuid = "binding-gmail";

        MapDatabaseRepository<Skill> skillRepo = new MapDatabaseRepository<>(Skill.class);
        MapDatabaseRepository<SkillGroup> groupRepo = new MapDatabaseRepository<>(SkillGroup.class);
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        MapDatabaseRepository<AgentTemplate> agentRepo = new MapDatabaseRepository<>(AgentTemplate.class);

        SkillService service = new SkillService(skillRepo, groupRepo, sessionRepo);
        ReflectionTestUtils.setField(service, "agentTemplateRepo", agentRepo);
        ReflectionTestUtils.setField(service, "typeGeneratorService", mock(sh.vork.typegen.TypeGeneratorService.class));

        BindingContractService contractService = mock(BindingContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ReflectionTestUtils.setField(service, "bindingContractService", contractService);
        ReflectionTestUtils.setField(service, "reflectionService", reflectionService);

        skillRepo.save(new Skill(
                "skill-mail",
                "Read Email",
                "desc",
                "grp-mail",
                SkillVisibility.PUBLIC,
                List.of(new SkillParameter("emailBinding", expectedContractVid, "Email provider binding", SkillParameterInputMode.AI_REQUIRED)),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                List.of()));

        agentRepo.save(new AgentTemplate(
                "agent-1",
                "Mail Agent",
                "",
                List.of(),
                false,
                List.of("skill-mail"),
                AgentType.INTERACTIVE,
                List.of(bindingUuid)));

        String sessionUuid = "session-mail";
        sessionRepo.save(new AiSession(
                sessionUuid,
                "GEMINI",
                SessionOriginMode.WEB,
                "lee",
                "Mail Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(),
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of()));

        when(contractService.getContract(expectedContractVid)).thenReturn(new BindingContract(
                expectedContractVid,
                "Email Contract",
                "",
                List.of(),
                "mail",
                "email",
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                System.currentTimeMillis(),
                System.currentTimeMillis()));
        when(reflectionService.getBindingByUuid(bindingUuid)).thenReturn(new ReflectionBinding(
                bindingUuid,
                "reflection-1",
                "gmail",
                "",
                Map.of(),
                1L,
                System.currentTimeMillis(),
                System.currentTimeMillis()));
        when(reflectionService.getBindingGroup(org.mockito.ArgumentMatchers.any())).thenReturn(new ReflectionGroup(
                "group-mail",
                "email",
                "Email",
                "",
                ReflectionType.REST,
                "",
                true,
                List.of(),
                List.of(),
                ReflectionAuthenticationMode.NONE,
                "",
                List.of(wrongContractVid),
                "mail",
                "email",
                "SNAPSHOT",
                sh.vork.artifact.ArtifactStatus.SNAPSHOT,
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        ToolExecutionContext.bindSessionUuid(sessionUuid);
        String result = service.executeSkill("skill-mail", Map.of("emailBinding", bindingUuid));

        assertTrue(result.contains("\"status\":\"invalid_parameters\""));
        assertTrue(result.contains("does not implement contract"));
    }
}
