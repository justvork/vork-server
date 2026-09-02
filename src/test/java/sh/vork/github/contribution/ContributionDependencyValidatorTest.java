package sh.vork.github.contribution;

import sh.vork.artifact.ArtifactStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.oauth.OAuthTemplateEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillGroup;
import sh.vork.skill.SkillVisibility;
import sh.vork.surface.Surface;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContributionDependencyValidatorTest {

    @Mock
    private DatabaseRepository<AgentTemplate> agentRepository;

    @Mock
    private DatabaseRepository<ScheduledJob> jobRepository;

    @Mock
    private DatabaseRepository<Surface> surfaceRepository;

    @Mock
    private DatabaseRepository<SkillGroup> skillGroupRepository;

    @Mock
    private DatabaseRepository<Skill> skillRepository;

    @Mock
    private DatabaseRepository<ReflectionGroup> reflectionGroupRepository;

        @Mock
        private DatabaseRepository<Reflection> reflectionRepository;

    @Mock
    private DatabaseRepository<ReflectionBinding> reflectionBindingRepository;

    @Mock
    private DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository;

    @Test
    void validateReflectionGroupFailsWhenOauthTemplateIsNotStaged() {
        ContributionDependencyValidator validator = new ContributionDependencyValidator(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                skillRepository,
                reflectionGroupRepository,
                reflectionRepository,
                reflectionBindingRepository,
                oauthTemplateRepository);

        ReflectionGroup reflectionGroup = new ReflectionGroup(
                "rg-1",
                "demo",
                "Demo",
                "desc",
                ReflectionType.REST,
                "https://api.example.test",
                true,
                List.of(),
                List.of(),
                sh.vork.reflection.ReflectionAuthenticationMode.OAUTH,
                "oauth-1",
                "demo",
                "rg",
                "SNAPSHOT",
                sh.vork.artifact.ArtifactStatus.SNAPSHOT,
                1L,
                1L);

        OAuthTemplateEntity oauthTemplate = new OAuthTemplateEntity(
                "oauth-1",
                "OAuth Template",
                "demo-client",
                "desc",
                "https://auth.example.test",
                "https://token.example.test",
                List.of("read"),
                Map.of(),
                sh.vork.artifact.ArtifactStatus.SNAPSHOT,
                1L,
                1L);

        when(reflectionGroupRepository.get("rg-1")).thenReturn(reflectionGroup);
        when(oauthTemplateRepository.get("oauth-1")).thenReturn(oauthTemplate);

        ContributionDependencyValidator.DependencyValidationReport report =
                validator.validateReflectionGroup("rg-1");

        assertFalse(report.valid());
        assertEquals(1, report.issues().size());
        assertEquals("oauth-template", report.issues().get(0).componentType());
        assertEquals("SNAPSHOT", report.issues().get(0).status());
    }

    @Test
    void validateSkillGroupHandlesSubSkillCyclesWithoutOverflow() {
        ContributionDependencyValidator validator = new ContributionDependencyValidator(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                skillRepository,
                reflectionGroupRepository,
                reflectionRepository,
                reflectionBindingRepository,
                oauthTemplateRepository);

        Skill skillA = new Skill(
                "skill-a",
                "Skill A",
                "",
                "sg-1",
                SkillVisibility.PUBLIC,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of("skill-b"),
                null,
                null,
                null,
                1L,
                1L,
                1L,
                List.of(),
                List.of());

        Skill skillB = new Skill(
                "skill-b",
                "Skill B",
                "",
                "sg-1",
                SkillVisibility.PUBLIC,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of("skill-a"),
                null,
                null,
                null,
                1L,
                1L,
                1L,
                List.of(),
                List.of());

        SkillGroup group = new SkillGroup(
                "sg-1",
                "Skill Group",
                "author",
                "category",
                List.of(skillA, skillB),
                "demo",
                "skills",
                "SNAPSHOT",
                sh.vork.artifact.ArtifactStatus.SNAPSHOT,
                1L,
                1L);

        when(skillGroupRepository.get("sg-1")).thenReturn(group);
        when(skillRepository.get("skill-a")).thenReturn(skillA);
        when(skillRepository.get("skill-b")).thenReturn(skillB);

        ContributionDependencyValidator.DependencyValidationReport report =
                validator.validateSkillGroup("sg-1");

        assertTrue(report.valid());
        assertFalse(report.cycles().isEmpty());
    }

    @Test
    void validateAgentDoesNotTreatSkillOwnerGroupAsDependency() {
        ContributionDependencyValidator validator = new ContributionDependencyValidator(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                skillRepository,
                reflectionGroupRepository,
                reflectionRepository,
                reflectionBindingRepository,
                oauthTemplateRepository);

        AgentTemplate agent = new AgentTemplate(
                "agent-1",
                "Agent",
                "prompt",
                List.of(),
                false,
                List.of("skill-1"),
                AgentType.INTERACTIVE,
                List.of(),
                List.of(),
                List.of(),
                null,
                "demo",
                "agent",
                "SNAPSHOT",
                sh.vork.artifact.ArtifactStatus.SNAPSHOT);

        Skill skill = new Skill(
                "skill-1",
                "Skill 1",
                "",
                "missing-owner-group",
                SkillVisibility.PUBLIC,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                1L,
                1L,
                1L,
                List.of(),
                List.of());

        when(agentRepository.get("agent-1")).thenReturn(agent);
        when(skillRepository.get("skill-1")).thenReturn(skill);

        ContributionDependencyValidator.DependencyValidationReport report =
                validator.validateAgent("agent-1");

        assertTrue(report.valid());
        assertTrue(report.issues().isEmpty());
    }
}
