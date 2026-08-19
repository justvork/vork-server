package sh.vork.mcp.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.model.McpBindingTool;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class McpRuntimeToolService {

    private static final Logger log = LoggerFactory.getLogger(McpRuntimeToolService.class);

    private final DatabaseRepository<McpBinding> bindingRepository;
    private final DatabaseRepository<McpBindingTool> toolRepository;
    private final McpToolCallbackFactory toolCallbackFactory;

    public McpRuntimeToolService(DatabaseRepository<McpBinding> bindingRepository,
                                 DatabaseRepository<McpBindingTool> toolRepository,
                                 McpToolCallbackFactory toolCallbackFactory) {
        this.bindingRepository = bindingRepository;
        this.toolRepository = toolRepository;
        this.toolCallbackFactory = toolCallbackFactory;
    }

    public List<ToolCallback> listActiveToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> callbackNames = new LinkedHashSet<>();

        try (var bindings = bindingRepository.search(0, 10_000, "name", SortOrder.ASC,
                SearchQuery.eq("status", McpBindingStatus.ACTIVE.name()))) {
            for (McpBinding binding : bindings.toList()) {
                try (var tools = toolRepository.search(0, 10_000, "toolName", SortOrder.ASC,
                        SearchQuery.eq("bindingUuid", binding.uuid()))) {
                    for (McpBindingTool tool : tools.toList()) {
                        if (!tool.enabled()) {
                            continue;
                        }
                        ToolCallback callback = toolCallbackFactory.create(binding, tool);
                        String callbackName = callback.getToolDefinition().name();
                        if (callbackNames.add(callbackName)) {
                            callbacks.add(callback);
                        } else {
                            log.warn("Skipping duplicate MCP runtime tool callback name [name={}, bindingUuid={}, toolId={}]",
                                    callbackName, binding.uuid(), tool.toolId());
                        }
                    }
                }
            }
        }

        return callbacks;
    }

    public List<ToolCallback> listToolCallbacksForBindings(List<String> bindingUuids) {
        if (bindingUuids == null || bindingUuids.isEmpty()) {
            return List.of();
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> callbackNames = new LinkedHashSet<>();
        Set<String> visitedBindings = new LinkedHashSet<>();

        for (String bindingUuid : bindingUuids) {
            if (bindingUuid == null || bindingUuid.isBlank() || !visitedBindings.add(bindingUuid)) {
                continue;
            }

            McpBinding binding = bindingRepository.get(bindingUuid);
            if (binding == null) {
                continue;
            }
            if (binding.status() != McpBindingStatus.ACTIVE) {
                continue;
            }

            try (var tools = toolRepository.search(0, 10_000, "toolName", SortOrder.ASC,
                    SearchQuery.eq("bindingUuid", binding.uuid()))) {
                for (McpBindingTool tool : tools.toList()) {
                    if (!tool.enabled()) {
                        continue;
                    }
                    ToolCallback callback = toolCallbackFactory.create(binding, tool);
                    String callbackName = callback.getToolDefinition().name();
                    if (callbackNames.add(callbackName)) {
                        callbacks.add(callback);
                    } else {
                        log.warn("Skipping duplicate MCP runtime tool callback name [name={}, bindingUuid={}, toolId={}]",
                                callbackName, binding.uuid(), tool.toolId());
                    }
                }
            }
        }

        return callbacks;
    }
}
