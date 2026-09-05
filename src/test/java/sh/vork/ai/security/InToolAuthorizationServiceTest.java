package sh.vork.ai.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.orm.DatabaseRepository;
import sh.vork.security.VorkUser;

class InToolAuthorizationServiceTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        ToolExecutionContext.clear();
    }

    @Test
    void requireAdminApproval_returnsWhenApprovalRuleAlreadyExists() {
        AuthorizationRuleEngine ruleEngine = mock(AuthorizationRuleEngine.class);
                @SuppressWarnings("unchecked")
                ObjectProvider<AuthorizationRuleEngine> ruleEngineProvider = mock(ObjectProvider.class);
                when(ruleEngineProvider.getIfAvailable()).thenReturn(ruleEngine);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepository = mock(DatabaseRepository.class);
                InToolAuthorizationService service = new InToolAuthorizationService(ruleEngineProvider, userRepository);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));
        ToolExecutionContext.put(SecuredToolCallback.CURRENT_TOOL_CALL_ID_CONTEXT_KEY, "tool-call-1");
        when(ruleEngine.requiresAuthorization("delegateTask", "alice", "tool-call-1", true)).thenReturn(false);

        assertDoesNotThrow(() -> service.requireAdminApproval(
                "delegateTask",
                "{\"target\":\"triage\"}",
                "Delegation Authorization Required",
                "Admin approval required.",
                "details"));
        verify(ruleEngine).requiresAuthorization(eq("delegateTask"), eq("alice"), eq("tool-call-1"), eq(true));
    }

    @Test
    void requireAdminApproval_throwsSuspensionWithAdminCampaignWhenNotAuthorized() {
        AuthorizationRuleEngine ruleEngine = mock(AuthorizationRuleEngine.class);
                @SuppressWarnings("unchecked")
                ObjectProvider<AuthorizationRuleEngine> ruleEngineProvider = mock(ObjectProvider.class);
                when(ruleEngineProvider.getIfAvailable()).thenReturn(ruleEngine);
        @SuppressWarnings("unchecked")
        DatabaseRepository<VorkUser> userRepository = mock(DatabaseRepository.class);
                InToolAuthorizationService service = new InToolAuthorizationService(ruleEngineProvider, userRepository);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));
        ToolExecutionContext.put(SecuredToolCallback.CURRENT_TOOL_CALL_ID_CONTEXT_KEY, "tool-call-2");

        when(ruleEngine.requiresAuthorization("delegateTask", "alice", "tool-call-2", true)).thenReturn(true);
        when(userRepository.list(0, Integer.MAX_VALUE)).thenReturn(List.of(
                new VorkUser("admin-1", "Admin One", "hash", "ADMIN", true, 1L, 1L),
                new VorkUser("admin-2", "Admin Two", "hash", "admin", true, 1L, 1L),
                new VorkUser("disabled-admin", "Disabled", "hash", "ADMIN", false, 1L, 1L),
                new VorkUser("user-1", "User", "hash", "USER", true, 1L, 1L)
        ).stream());

        ToolSuspensionException ex = assertThrows(ToolSuspensionException.class,
                () -> service.requireAdminApproval(
                        "delegateTask",
                        "{\"target\":\"triage\"}",
                        "Delegation Authorization Required",
                        "Administrator approval is required before this action can continue.",
                        null));

        assertEquals("delegateTask", ex.getToolName());
        assertNotNull(ex.getFormSchema());
        assertEquals("AUTHORIZE_TOOL", ex.getFormSchema().intent());
        assertNotNull(ex.getSuspensionCampaign());
        assertEquals(List.of("admin-1", "admin-2"), ex.getSuspensionCampaign().channelNames());
        assertTrue(ex.getFormSchema().actions().stream().anyMatch(a -> "ONCE".equals(a.name())));
        assertTrue(ex.getFormSchema().actions().stream().anyMatch(a -> "DENIED".equals(a.name())));
    }
}
