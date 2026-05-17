package sh.vork.ai.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuthorizationRuleEngine}.
 *
 * <p>No Spring context is required — tests use the package-visible
 * {@code AuthorizationRuleEngine(Set<String>)} constructor to pre-seed
 * the restricted tool names.
 */
class AuthorizationRuleEngineTest {

    private static final String RESTRICTED_TOOL   = "compileJavaType";
    private static final String UNRESTRICTED_TOOL = "listJavaTypes";
    private static final String ALICE             = "alice";
    private static final String BOB               = "bob";
    private static final String CALL_ID           = "call-abc-123";

    // Engine where only RESTRICTED_TOOL is marked @Restricted
    private AuthorizationRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AuthorizationRuleEngine(Set.of(RESTRICTED_TOOL));
    }

    // ── 1. Unannotated (unrestricted) tools ───────────────────────────────────

    @Nested
    @DisplayName("Unrestricted tools")
    class UnrestrictedTools {

        @Test
        @DisplayName("pass without any rules registered")
        void unrestrictedTool_alwaysPasses() {
            assertFalse(engine.requiresAuthorization(UNRESTRICTED_TOOL, ALICE, CALL_ID),
                    "An unannotated tool must never require authorization");
        }

        @Test
        @DisplayName("pass even for users with no exception rules")
        void unrestrictedTool_passesForAnyUser() {
            assertFalse(engine.requiresAuthorization(UNRESTRICTED_TOOL, BOB, "call-xyz"));
        }
    }

    // ── 2. Restricted tool with no exception rules ────────────────────────────

    @Nested
    @DisplayName("Restricted tool — no exception rules")
    class RestrictedToolNoRules {

        @Test
        @DisplayName("blocks when no user rules are registered")
        void restrictedTool_blockedWithNoRules() {
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, CALL_ID),
                    "A @Restricted tool with no exception rules must require authorization");
        }

        @Test
        @DisplayName("blocks for every user independently")
        void restrictedTool_blockedForAllUsers() {
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, "c1"));
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, BOB,   "c2"));
        }
    }

    // ── 3. Temporary user rules ───────────────────────────────────────────────

    @Nested
    @DisplayName("Temporary user rules")
    class TemporaryUserRules {

        @Test
        @DisplayName("allow the granted user while others remain blocked")
        void temporaryRule_grantsOnlyTargetUser() {
            engine.addTemporaryUserRule(ALICE, RESTRICTED_TOOL);

            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, "c1"),
                    "Alice has a temporary rule — she must be allowed");
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, BOB, "c2"),
                    "Bob has no rule — he must still be blocked");
        }

        @Test
        @DisplayName("rule persists across multiple calls for the same user")
        void temporaryRule_persistsForGrantedUser() {
            engine.addTemporaryUserRule(ALICE, RESTRICTED_TOOL);

            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, "first-call"));
            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, "second-call"));
        }

        @Test
        @DisplayName("unrestricted tool is unaffected by adding a temporary rule")
        void temporaryRule_doesNotAffectUnrestrictedTool() {
            engine.addTemporaryUserRule(ALICE, RESTRICTED_TOOL);

            assertFalse(engine.requiresAuthorization(UNRESTRICTED_TOOL, ALICE, CALL_ID),
                    "Unrestricted tools must still pass regardless of any rules");
        }
    }

    // ── 4. Permanent rules ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Permanent rules")
    class PermanentRules {

        @Test
        @DisplayName("allow the granted user while others remain blocked")
        void permanentRule_grantsOnlyTargetUser() {
            engine.addPermanentRule(ALICE, RESTRICTED_TOOL);

            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, "c1"),
                    "Alice has a permanent rule — she must be allowed");
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, BOB, "c2"),
                    "Bob has no rule — he must still be blocked");
        }
    }

    // ── 5. Use-once exceptions ────────────────────────────────────────────────

    @Nested
    @DisplayName("Use-once exceptions")
    class UseOnceExceptions {

        @Test
        @DisplayName("passes on first use and blocks on subsequent calls with same ID")
        void useOnce_consumedOnFirstMatch() {
            engine.addUseOnceRule(CALL_ID);

            // First call: token should be consumed and the call allowed
            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, CALL_ID),
                    "First call with use-once token must be allowed");

            // Second call with same ID: token has been consumed — must block again
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, CALL_ID),
                    "Second call with same call ID must be blocked after token is consumed");
        }

        @Test
        @DisplayName("does not match a different call ID")
        void useOnce_doesNotMatchDifferentId() {
            engine.addUseOnceRule("different-call-id");

            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, CALL_ID),
                    "A use-once token for a different ID must not grant access");
        }

        @Test
        @DisplayName("null call ID does not consume a use-once token")
        void useOnce_nullCallIdIsIgnored() {
            engine.addUseOnceRule(CALL_ID);

            // Passing null as toolCallId should not match the token
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, null),
                    "Null call ID must not match any use-once token");

            // Token must still be intact
            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, CALL_ID),
                    "Token should still be valid after null-ID call was ignored");
        }

        @Test
        @DisplayName("each token is independent — one consumption does not affect others")
        void useOnce_independentTokens() {
            engine.addUseOnceRule("token-A");
            engine.addUseOnceRule("token-B");

            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, "token-A"));
            // token-A consumed; token-B should still work
            assertFalse(engine.requiresAuthorization(RESTRICTED_TOOL, BOB,   "token-B"));
            // both tokens consumed
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, ALICE, "token-A"));
            assertTrue(engine.requiresAuthorization(RESTRICTED_TOOL, BOB,   "token-B"));
        }
    }
}
