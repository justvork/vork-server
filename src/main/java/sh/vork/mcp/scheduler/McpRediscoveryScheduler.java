package sh.vork.mcp.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.service.McpBindingService;

@Component
public class McpRediscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(McpRediscoveryScheduler.class);

    private final McpBindingService bindingService;
    private final boolean enabled;
    private final int maxRetries;

    public McpRediscoveryScheduler(McpBindingService bindingService,
                                   @Value("${mcp.rediscovery.enabled:true}") boolean enabled,
                                   @Value("${mcp.rediscovery.max-retries:2}") int maxRetries) {
        this.bindingService = bindingService;
        this.enabled = enabled;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Scheduled(fixedDelayString = "${mcp.rediscovery.interval:86400000}")
    public void runRediscoverySweep() {
        if (!enabled) {
            return;
        }

        int attempted = 0;
        int succeeded = 0;
        int failed = 0;

        for (McpBinding binding : bindingService.list()) {
            if (binding.baseUrl() == null || binding.baseUrl().isBlank()) {
                continue;
            }
            attempted++;
            if (refreshWithRetry(binding)) {
                succeeded++;
            } else {
                failed++;
            }
        }

        log.debug("MCP rediscovery sweep complete [attempted={}, succeeded={}, failed={}]",
                attempted, succeeded, failed);
    }

    @Scheduled(fixedDelayString = "${mcp.rediscovery.error-interval:30000}")
    public void runErrorRecoverySweep() {
        if (!enabled) {
            return;
        }

        int attempted = 0;
        int recovered = 0;
        int failed = 0;

        for (McpBinding binding : bindingService.list()) {
            if (binding.status() != sh.vork.mcp.model.McpBindingStatus.ERROR) {
                continue;
            }
            if (binding.baseUrl() == null || binding.baseUrl().isBlank()) {
                continue;
            }
            attempted++;
            if (refreshWithRetry(binding)) {
                recovered++;
            } else {
                failed++;
            }
        }

        if (attempted > 0) {
            log.debug("MCP error recovery sweep complete [attempted={}, recovered={}, failed={}]",
                    attempted, recovered, failed);
        }
    }

    private boolean refreshWithRetry(McpBinding binding) {
        int attempts = 0;
        RuntimeException last = null;
        while (attempts <= maxRetries) {
            attempts++;
            try {
                bindingService.refreshDriftStatus(binding.uuid());
                return true;
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("MCP rediscovery attempt failed [bindingUuid={}, attempt={}, maxAttempts={}, error={}]",
                        binding.uuid(), attempts, maxRetries + 1, ex.getMessage());
            }
        }

        if (last != null) {
            log.warn("MCP rediscovery failed after retries [bindingUuid={}, error={}]",
                    binding.uuid(), last.getMessage());
        }
        return false;
    }
}
