package sh.vork.ai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.vork.orm.DatabaseRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ApprovalPolicyServiceTest {

    private DatabaseRepository<ApprovalPolicy> policyRepo;
    private DatabaseRepository<ApprovalPolicyAssignment> assignmentRepo;
    private ApprovalPolicyService service;

    private final Map<String, ApprovalPolicy> policies = new LinkedHashMap<>();
    private final Map<String, ApprovalPolicyAssignment> assignments = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        policyRepo = mock(DatabaseRepository.class);
        assignmentRepo = mock(DatabaseRepository.class);

        when(policyRepo.get(anyString())).thenAnswer(inv -> policies.get(inv.getArgument(0)));
        doAnswer(inv -> {
            ApprovalPolicy policy = inv.getArgument(0);
            policies.put(policy.uuid(), policy);
            return null;
        }).when(policyRepo).save(any(ApprovalPolicy.class));
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            policies.remove(id);
            return null;
        }).when(policyRepo).delete(anyString());
        when(policyRepo.list(0, Integer.MAX_VALUE)).thenAnswer(_inv -> policies.values().stream());

        when(assignmentRepo.get(anyString())).thenAnswer(inv -> assignments.get(inv.getArgument(0)));
        doAnswer(inv -> {
            ApprovalPolicyAssignment assignment = inv.getArgument(0);
            assignments.put(assignment.uuid(), assignment);
            return null;
        }).when(assignmentRepo).save(any(ApprovalPolicyAssignment.class));
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            assignments.remove(id);
            return null;
        }).when(assignmentRepo).delete(anyString());
        when(assignmentRepo.list(0, Integer.MAX_VALUE)).thenAnswer(_inv -> {
            Stream<ApprovalPolicyAssignment> stream = assignments.values().stream();
            return stream;
        });

        service = new ApprovalPolicyService(policyRepo, assignmentRepo);
    }

    @Test
    void createPolicy_persistsAndLists() {
        ApprovalPolicy policy = service.createPolicy(new ApprovalPolicy(
                null,
                "Finance",
                true,
                java.util.List.of("bob"),
                java.util.List.of(),
                0,
                0));
        assertNotNull(policy);
        assertNotNull(policy.uuid());
        assertEquals(1, service.listPolicies().size());
    }

    @Test
    void createPolicy_requiresChannels() {
        ApprovalPolicy invalid = new ApprovalPolicy(
                null,
                "Custom",
                true,
                java.util.List.of(),
                java.util.List.of(),
                0,
                0);

        assertThrows(IllegalArgumentException.class, () -> service.createPolicy(invalid));
    }

    @Test
    void assignment_canBeSavedAndCleared() {
        ApprovalPolicy policy = service.createPolicy(new ApprovalPolicy(
                null,
                "Ops",
                true,
                java.util.List.of("alice"),
                java.util.List.of(),
                0,
                0));

        service.assignPolicy("agent", "agent-1", policy.uuid());
        Map<String, String> map = service.listAssignmentsByTargetType("agent");
        assertEquals(policy.uuid(), map.get("agent-1"));

        service.assignPolicy("agent", "agent-1", "");
        Map<String, String> mapAfterDelete = service.listAssignmentsByTargetType("agent");
        assertNull(mapAfterDelete.get("agent-1"));
    }

    @Test
    void deletePolicy_removesDependentAssignments() {
        ApprovalPolicy policy = service.createPolicy(new ApprovalPolicy(
                null,
                "HR",
                true,
                java.util.List.of("carol"),
                java.util.List.of(),
                0,
                0));
        service.assignPolicy("skill", "skill-1", policy.uuid());
        assertEquals(policy.uuid(), service.listAssignmentsByTargetType("skill").get("skill-1"));

        service.deletePolicy(policy.uuid());

        assertNull(service.getPolicy(policy.uuid()));
        assertFalse(service.listAssignmentsByTargetType("skill").containsKey("skill-1"));
    }

    @Test
    void createPolicy_rejectsDuplicateNameIgnoringCase() {
        service.createPolicy(new ApprovalPolicy(
                null,
                "Finance",
                true,
                java.util.List.of("alice"),
                java.util.List.of(),
                0,
                0));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.createPolicy(new ApprovalPolicy(
                        null,
                        " finance ",
                        true,
                        java.util.List.of("bob"),
                        java.util.List.of(),
                        0,
                        0)));

        assertTrue(ex.getMessage().contains("policy name must be unique"));
    }

    @Test
    void getPolicyByName_findsUniqueMatch() {
        ApprovalPolicy created = service.createPolicy(new ApprovalPolicy(
                null,
                "Operations",
                true,
                java.util.List.of("alice"),
                java.util.List.of(),
                0,
                0));

        ApprovalPolicy found = service.getPolicyByName(" operations ");
        assertNotNull(found);
        assertEquals(created.uuid(), found.uuid());
    }
}
