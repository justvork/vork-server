package sh.vork.mcp.scheduler;

import org.junit.jupiter.api.Test;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.model.McpTransportMode;
import sh.vork.mcp.service.McpBindingService;
import sh.vork.reflection.ArtifactStatus;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpRediscoverySchedulerTest {

    @Test
    void sweepSkipsWhenDisabled() {
        McpBindingService service = mock(McpBindingService.class);
        McpRediscoveryScheduler scheduler = new McpRediscoveryScheduler(service, false, 2);

        scheduler.runRediscoverySweep();

        verify(service, never()).list();
    }

    @Test
    void sweepRefreshesEligibleBindings() {
        McpBindingService service = mock(McpBindingService.class);
        McpRediscoveryScheduler scheduler = new McpRediscoveryScheduler(service, true, 2);

        when(service.list()).thenReturn(List.of(
                binding("b-1", "https://mcp-1.example"),
                binding("b-2", "")
        ));

        scheduler.runRediscoverySweep();

        verify(service, times(1)).refreshDriftStatus("b-1");
        verify(service, never()).refreshDriftStatus("b-2");
    }

    @Test
    void sweepRetriesFailuresWithinLimit() {
        McpBindingService service = mock(McpBindingService.class);
        McpRediscoveryScheduler scheduler = new McpRediscoveryScheduler(service, true, 2);

        when(service.list()).thenReturn(List.of(binding("b-1", "https://mcp-1.example")));
        when(service.refreshDriftStatus("b-1"))
                .thenThrow(new IllegalStateException("fail-1"))
                .thenThrow(new IllegalStateException("fail-2"))
                .thenReturn(binding("b-1", "https://mcp-1.example"));

        scheduler.runRediscoverySweep();

        verify(service, times(3)).refreshDriftStatus("b-1");
    }

    @Test
    void errorRecoverySweepOnlyRefreshesErrorBindings() {
        McpBindingService service = mock(McpBindingService.class);
        McpRediscoveryScheduler scheduler = new McpRediscoveryScheduler(service, true, 1);

        when(service.list()).thenReturn(List.of(
                binding("b-err", "https://mcp-err.example", McpBindingStatus.ERROR),
                binding("b-active", "https://mcp-ok.example", McpBindingStatus.ACTIVE),
                binding("b-empty", "", McpBindingStatus.ERROR)
        ));

        scheduler.runErrorRecoverySweep();

        verify(service, times(1)).refreshDriftStatus("b-err");
        verify(service, never()).refreshDriftStatus("b-active");
        verify(service, never()).refreshDriftStatus("b-empty");
    }

    private static McpBinding binding(String uuid, String baseUrl) {
        return binding(uuid, baseUrl, McpBindingStatus.INACTIVE);
    }

    private static McpBinding binding(String uuid, String baseUrl, McpBindingStatus status) {
        return new McpBinding(
                uuid,
                uuid,
                status,
                baseUrl,
                McpTransportMode.STREAMABLE_HTTP,
                "",
                "demo",
                "artifact",
                "1.0",
                ArtifactStatus.SNAPSHOT,
                0L,
                "",
                "",
                0L,
                0L);
    }
}
