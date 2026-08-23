package sh.vork.ai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.orm.DatabaseRepository;
import sh.vork.skill.SkillFrame;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ApprovalPolicyRuntimeResolverTest {

    private DatabaseRepository<AiSession> sessionRepo;
    private ApprovalPolicyService policyService;
    private ApprovalPolicyRuntimeResolver resolver;

    private final Map<String, AiSession> sessionStore = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        sessionRepo = mock(DatabaseRepository.class);
        when(sessionRepo.get(anyString())).thenAnswer(inv -> sessionStore.get(inv.getArgument(0)));

        DatabaseRepository<ApprovalPolicy> policyRepo = mock(DatabaseRepository.class);
        DatabaseRepository<ApprovalPolicyAssignment> assignmentRepo = mock(DatabaseRepository.class);

        Map<String, ApprovalPolicy> policies = new LinkedHashMap<>();
        policies.put("policy-1", new ApprovalPolicy(
            "policy-1",
            "Policy 1",
                true,
                List.of("bob"),
                List.of(),
            System.currentTimeMillis(),
                System.currentTimeMillis()
        ));

        Map<String, ApprovalPolicyAssignment> assignments = new LinkedHashMap<>();

        when(policyRepo.get(anyString())).thenAnswer(inv -> policies.get(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            ApprovalPolicy policy = inv.getArgument(0);
            policies.put(policy.uuid(), policy);
            return null;
        }).when(policyRepo).save(org.mockito.ArgumentMatchers.any(ApprovalPolicy.class));

        when(assignmentRepo.list(0, Integer.MAX_VALUE)).thenAnswer(_inv -> assignments.values().stream());
        org.mockito.Mockito.doAnswer(inv -> {
            ApprovalPolicyAssignment assignment = inv.getArgument(0);
            assignments.put(assignment.uuid(), assignment);
            return null;
        }).when(assignmentRepo).save(org.mockito.ArgumentMatchers.any(ApprovalPolicyAssignment.class));
        org.mockito.Mockito.doAnswer(inv -> {
            assignments.remove(inv.getArgument(0));
            return null;
        }).when(assignmentRepo).delete(anyString());

        policyService = new ApprovalPolicyService(policyRepo, assignmentRepo);
        resolver = new ApprovalPolicyRuntimeResolver(policyService, sessionRepo);
    }

    @Test
    void resolveCampaign_returnsNullWithoutAssignment() {
        AiSession session = new AiSession(
                "s1",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "test",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(),
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of());
        sessionStore.put(session.uuid(), session);

        ToolSuspensionException.SuspensionCampaign campaign = resolver.resolveCampaign("s1", "compileJavaType");
        assertNull(campaign);
    }

    @Test
    void resolveCampaign_usesSkillAssignmentBeforeAgentAssignment() {
        policyService.assignPolicy("agent", "agent-1", "policy-1");
        policyService.assignPolicy("skill", "skill-1", "policy-1");

        AiSession session = new AiSession(
                "s2",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "test",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(),
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(new SkillFrame("skill-1", "Skill 1", "", List.of(), List.of(), Map.of(), 0)),
                List.of(),
                List.of());
        sessionStore.put(session.uuid(), session);

        ToolSuspensionException.SuspensionCampaign campaign = resolver.resolveCampaign("s2", "compileJavaType");
        assertNotNull(campaign);
        assertEquals(List.of("bob"), campaign.channelNames());
        assertEquals(sh.vork.ai.request.RequestResponsePolicy.FIRST, campaign.responsePolicy());
    }

    @Test
    void resolveCampaign_prefersSessionOverrideChannels() {
        AiSession session = new AiSession(
                "s3",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "test",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(ApprovalPolicyRuntimeResolver.SESSION_APPROVAL_OVERRIDE_ENV,
                        "{\"enabled\":true,\"channels\":[\"alice\"],\"responsePolicy\":\"ALL\"}"),
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of());
        sessionStore.put(session.uuid(), session);

        ToolSuspensionException.SuspensionCampaign campaign = resolver.resolveCampaign("s3", "compileJavaType");
        assertNotNull(campaign);
        assertEquals(List.of("alice"), campaign.channelNames());
        assertEquals(sh.vork.ai.request.RequestResponsePolicy.ALL, campaign.responsePolicy());
    }

    @Test
    void resolveCampaign_sessionOverrideDisabledSuppressesCampaign() {
        policyService.assignPolicy("agent", "agent-1", "policy-1");

        AiSession session = new AiSession(
                "s4",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "test",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of(ApprovalPolicyRuntimeResolver.SESSION_APPROVAL_OVERRIDE_ENV,
                        "{\"enabled\":false,\"channels\":[\"alice\"]}"),
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of());
        sessionStore.put(session.uuid(), session);

        ToolSuspensionException.SuspensionCampaign campaign = resolver.resolveCampaign("s4", "compileJavaType");
        assertNull(campaign);
    }
}
