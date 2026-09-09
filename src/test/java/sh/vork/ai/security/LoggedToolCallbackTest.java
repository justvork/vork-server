package sh.vork.ai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.service.ToolInvocationPersistenceService;

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

    @Test
    void call_whenRegularTool_persistsInvocationRecord() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        ToolInvocationPersistenceService persistence = mock(ToolInvocationPersistenceService.class);
        when(definition.name()).thenReturn("searchMailbox");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call("{\"query\":\"urgent\"}")).thenReturn("{\"status\":\"ok\"}");

        LoggedToolCallback callback = new LoggedToolCallback(delegate, persistence);

        ToolExecutionContext.bindSessionUuid("session-logged-tool-test");
        try {
            String result = callback.call("{\"query\":\"urgent\"}");
            assertEquals("{\"status\":\"ok\"}", result);

            verify(persistence).persistInvocation(
                    org.mockito.ArgumentMatchers.eq("searchMailbox"),
                    org.mockito.ArgumentMatchers.eq("{\"query\":\"urgent\"}"),
                    org.mockito.ArgumentMatchers.eq("{\"status\":\"ok\"}"),
                    org.mockito.ArgumentMatchers.eq(true),
                    org.mockito.ArgumentMatchers.eq(false),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.anyLong());
        } finally {
            ToolExecutionContext.complete("session-logged-tool-test");
        }
    }

    @Test
    void call_whenReadChatHistory_doesNotPersistInvocationRecord() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        ToolInvocationPersistenceService persistence = mock(ToolInvocationPersistenceService.class);
        when(definition.name()).thenReturn("readChatHistory");
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call("{\"reference\":\"last\"}")).thenReturn("{\"status\":\"ok\"}");

        LoggedToolCallback callback = new LoggedToolCallback(delegate, persistence);

        ToolExecutionContext.bindSessionUuid("session-logged-tool-test");
        try {
            callback.call("{\"reference\":\"last\"}");
            verify(persistence, never()).persistInvocation(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyLong());
        } finally {
            ToolExecutionContext.complete("session-logged-tool-test");
        }
    }
}
