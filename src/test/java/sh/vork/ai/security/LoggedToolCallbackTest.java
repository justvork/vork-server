package sh.vork.ai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;

class LoggedToolCallbackTest {

    @Test
    void call_whenSuspensionWrappedInRuntime_storesPendingSuspensionInContext() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("requestInformation");
        when(delegate.getToolDefinition()).thenReturn(definition);

        ToolSuspensionException suspension =
                new ToolSuspensionException("requestInformation", "{\"promptText\":\"when?\"}");

        when(delegate.call("{}"))
                .thenThrow(new RuntimeException("wrapper", suspension));

        LoggedToolCallback callback = new LoggedToolCallback(delegate);

        ToolExecutionContext.bindSessionUuid("session-logged-tool-test");
        try {
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> callback.call("{}"));
            assertEquals("wrapper", thrown.getMessage());

            Object pending = ToolExecutionContext.get(LoggedToolCallback.PENDING_TOOL_SUSPENSION_CONTEXT_KEY);
            assertNotNull(pending);
            assertTrue(pending instanceof ToolSuspensionException);
            ToolSuspensionException recovered = (ToolSuspensionException) pending;
            assertEquals("requestInformation", recovered.getToolName());
        } finally {
            ToolExecutionContext.complete("session-logged-tool-test");
        }
    }
}
