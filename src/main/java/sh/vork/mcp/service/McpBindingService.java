package sh.vork.mcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import sh.vork.attention.AttentionSignalService;
import sh.vork.mcp.client.McpClient;
import sh.vork.mcp.client.McpClientConfig;
import sh.vork.mcp.client.McpClientFactory;
import sh.vork.mcp.client.dto.McpDiscoverResult;
import sh.vork.mcp.client.dto.McpDiscoveredPrompt;
import sh.vork.mcp.client.dto.McpDiscoveredResource;
import sh.vork.mcp.client.dto.McpDiscoveredTool;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.model.McpBindingPrompt;
import sh.vork.mcp.model.McpBindingResource;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.model.McpBindingTool;
import sh.vork.mcp.model.McpToolParameterConfig;
import sh.vork.mcp.model.McpToolParameterInputMode;
import sh.vork.mcp.controller.dto.McpToolUpdateRequest;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;
import sh.vork.artifact.ArtifactStatus;
import sh.vork.security.SecureCredentialStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class McpBindingService {

    private static final Logger log = LoggerFactory.getLogger(McpBindingService.class);

    private final DatabaseRepository<McpBinding> bindingRepository;
    private final DatabaseRepository<McpBindingTool> toolRepository;
    private final DatabaseRepository<McpBindingResource> resourceRepository;
    private final DatabaseRepository<McpBindingPrompt> promptRepository;
    private final McpClientFactory clientFactory;
    private final McpContractHashService contractHashService;
    private final McpContractDiffService contractDiffService;
    private final SecureCredentialStore secureCredentialStore;

    @Autowired(required = false)
    private AttentionSignalService attentionSignalService;

    public McpBindingService(DatabaseRepository<McpBinding> bindingRepository,
                             DatabaseRepository<McpBindingTool> toolRepository,
                             DatabaseRepository<McpBindingResource> resourceRepository,
                             DatabaseRepository<McpBindingPrompt> promptRepository,
                             McpClientFactory clientFactory,
                             McpContractHashService contractHashService,
                             McpContractDiffService contractDiffService,
                             SecureCredentialStore secureCredentialStore) {
        this.bindingRepository = bindingRepository;
        this.toolRepository = toolRepository;
        this.resourceRepository = resourceRepository;
        this.promptRepository = promptRepository;
        this.clientFactory = clientFactory;
        this.contractHashService = contractHashService;
        this.contractDiffService = contractDiffService;
        this.secureCredentialStore = secureCredentialStore;
    }

    public List<McpBinding> list() {
        try (var stream = bindingRepository.search(0, 5_000, "name", SortOrder.ASC)) {
            return stream.toList();
        }
    }

    public McpBinding get(String uuid) {
        return bindingRepository.get(uuid);
    }

    public McpBinding createOrUpdate(CreateOrUpdateRequest request, String existingUuid) {
        log.debug("ENTER createOrUpdate: existingUuid={}, name={}, baseUrl={}",
                existingUuid, request.name(), request.baseUrl());

        validateNameUniqueness(request.name(), existingUuid);

        McpBinding existing = existingUuid == null ? null : bindingRepository.get(existingUuid);

        String username = currentUsername();
        String secretRef;
        if (existing != null && (request.authorization() == null || request.authorization().isBlank())) {
            secretRef = existing.authorizationSecretRef();
        } else {
            secretRef = saveAuthorizationSecretIfPresent(username, request.authorization());
        }

        long now = System.currentTimeMillis();
        String uuid = existing == null ? UUID.randomUUID().toString() : existing.uuid();

        McpBinding base = new McpBinding(
                uuid,
                request.name().trim(),
                McpBindingStatus.INACTIVE,
                request.baseUrl().trim(),
                request.transportMode(),
                secretRef,
                safe(request.groupId()),
                safe(request.artifactId()),
                request.version() == null || request.version().isBlank() ? "SNAPSHOT" : request.version().trim(),
                request.artifactStatus() == null ? ArtifactStatus.SNAPSHOT : request.artifactStatus(),
                existing == null ? 0L : existing.lastDiscoveredAt(),
                "",
                existing == null ? "" : safe(existing.lastContractHash()),
                existing == null ? now : existing.createdAt(),
                now
        );

        DiscoverySnapshot snapshot = discover(base, username);

        McpBinding saved = new McpBinding(
                base.uuid(),
                base.name(),
                McpBindingStatus.INACTIVE,
                base.baseUrl(),
                base.transportMode(),
                base.authorizationSecretRef(),
                base.groupId(),
                base.artifactId(),
                base.version(),
                base.artifactStatus(),
                snapshot.discoveredAt(),
                "",
                snapshot.contractHash(),
                base.createdAt(),
                now
        );

        bindingRepository.save(saved);
        replaceSnapshots(saved.uuid(), snapshot.discoverResult());

        log.debug("EXIT createOrUpdate: uuid={}, toolCount={}, resourceCount={}, promptCount={}",
                saved.uuid(), snapshot.discoverResult().tools().size(),
                snapshot.discoverResult().resources().size(), snapshot.discoverResult().prompts().size());

        return saved;
    }

    public DiscoverySnapshot validate(CreateOrUpdateRequest request) {
        log.debug("ENTER validate: name={}, baseUrl={}", request.name(), request.baseUrl());
        McpBinding base = new McpBinding(
                UUID.randomUUID().toString(),
                request.name(),
                McpBindingStatus.INACTIVE,
                request.baseUrl(),
                request.transportMode(),
                "",
                safe(request.groupId()),
                safe(request.artifactId()),
                safe(request.version()),
                request.artifactStatus() == null ? ArtifactStatus.SNAPSHOT : request.artifactStatus(),
                0,
                "",
                "",
                System.currentTimeMillis(),
                System.currentTimeMillis());

        DiscoverySnapshot snapshot = discover(base, currentUsername());
        log.debug("EXIT validate: hash={}", snapshot.contractHash());
        return snapshot;
    }

    public DiscoverySnapshot rediscover(String bindingUuid) {
        return sync(bindingUuid).snapshot();
    }

    public SyncResult sync(String bindingUuid) {
        McpBinding binding = requireBinding(bindingUuid);
        McpBindingStatus previousStatus = binding.status();
        McpDiscoverResult persisted = buildPersistedSnapshot(bindingUuid);
        DiscoverySnapshot snapshot = discover(binding, currentUsername());
        var diff = contractDiffService.diff(persisted, snapshot.discoverResult());
        boolean contractChanged = diff.drifted();
        McpBindingStatus nextStatus = contractChanged ? McpBindingStatus.INACTIVE : binding.status();

        McpBinding updated = new McpBinding(
                binding.uuid(),
                binding.name(),
                nextStatus,
                binding.baseUrl(),
                binding.transportMode(),
                binding.authorizationSecretRef(),
                binding.groupId(),
                binding.artifactId(),
                binding.version(),
                binding.artifactStatus(),
                snapshot.discoveredAt(),
                "",
                snapshot.contractHash(),
                binding.createdAt(),
                System.currentTimeMillis());

        bindingRepository.save(updated);
        publishMcpStatusChange(updated, previousStatus, nextStatus,
                contractChanged ? "Contract changed during sync and requires review." : null);
        if (contractChanged) {
            replaceSnapshots(bindingUuid, snapshot.discoverResult());
        }
        return new SyncResult(snapshot, diff, contractChanged, nextStatus);
    }

    public DriftInspection inspectDrift(String bindingUuid) {
        McpBinding binding = requireBinding(bindingUuid);
        McpDiscoverResult persisted = buildPersistedSnapshot(bindingUuid);
        DiscoverySnapshot snapshot = discover(binding, currentUsername());
        var diff = contractDiffService.diff(persisted, snapshot.discoverResult());
        return new DriftInspection(bindingUuid, binding.lastContractHash(), snapshot.contractHash(), snapshot.discoveredAt(), diff);
    }

    public McpBinding refreshDriftStatus(String bindingUuid) {
        McpBinding binding = requireBinding(bindingUuid);
        McpBindingStatus previousStatus = binding.status();
        try {
            DriftInspection inspection = inspectDrift(bindingUuid);
            McpBindingStatus nextStatus;
            if (inspection.diff().drifted()) {
                nextStatus = McpBindingStatus.DRIFTED;
            } else if (binding.status() == McpBindingStatus.ERROR) {
                // Clear stale error state once the endpoint is reachable again.
                nextStatus = McpBindingStatus.INACTIVE;
            } else {
                nextStatus = binding.status();
            }

            McpBinding updated = new McpBinding(
                    binding.uuid(),
                    binding.name(),
                    nextStatus,
                    binding.baseUrl(),
                    binding.transportMode(),
                    binding.authorizationSecretRef(),
                    binding.groupId(),
                    binding.artifactId(),
                    binding.version(),
                    binding.artifactStatus(),
                    inspection.discoveredAt(),
                    "",
                    binding.lastContractHash(),
                    binding.createdAt(),
                    System.currentTimeMillis());
            bindingRepository.save(updated);
            publishMcpStatusChange(updated, previousStatus, nextStatus,
                    inspection.diff().drifted() ? "Discovered contract drift during rediscovery." : null);
            return updated;
        } catch (RuntimeException ex) {
            String failure = ex.getMessage() == null ? "rediscovery failed" : ex.getMessage();
            McpBinding updated = new McpBinding(
                    binding.uuid(),
                    binding.name(),
                    McpBindingStatus.ERROR,
                    binding.baseUrl(),
                    binding.transportMode(),
                    binding.authorizationSecretRef(),
                    binding.groupId(),
                    binding.artifactId(),
                    binding.version(),
                    binding.artifactStatus(),
                    binding.lastDiscoveredAt(),
                    failure,
                    binding.lastContractHash(),
                    binding.createdAt(),
                    System.currentTimeMillis());
            bindingRepository.save(updated);
            publishMcpStatusChange(updated, previousStatus, McpBindingStatus.ERROR, failure);
            throw ex;
        }
    }

    public McpBinding activate(String bindingUuid) {
        log.debug("ENTER activate: bindingUuid={}", bindingUuid);
        McpBinding binding = requireBinding(bindingUuid);
        McpBindingStatus previousStatus = binding.status();

        if (binding.status() == McpBindingStatus.DRIFTED) {
            throw new IllegalStateException("Binding is drifted and cannot be activated until synced and reviewed.");
        }
        if (binding.lastDiscoveredAt() <= 0 || safe(binding.lastContractHash()).isBlank()) {
            throw new IllegalStateException("Binding has no discovered contract. Run validation/discovery first.");
        }

        McpBinding updated = new McpBinding(
                binding.uuid(),
                binding.name(),
                McpBindingStatus.ACTIVE,
                binding.baseUrl(),
                binding.transportMode(),
                binding.authorizationSecretRef(),
                binding.groupId(),
                binding.artifactId(),
                binding.version(),
                binding.artifactStatus(),
                binding.lastDiscoveredAt(),
                binding.lastDiscoveryError(),
                binding.lastContractHash(),
                binding.createdAt(),
                System.currentTimeMillis());
        bindingRepository.save(updated);
            publishMcpStatusChange(updated, previousStatus, updated.status(), null);
        log.debug("EXIT activate: bindingUuid={}, status={}", bindingUuid, updated.status());
        return updated;
    }

    public McpBinding deactivate(String bindingUuid) {
        log.debug("ENTER deactivate: bindingUuid={}", bindingUuid);
        McpBinding binding = requireBinding(bindingUuid);
        McpBindingStatus previousStatus = binding.status();
        McpBinding updated = new McpBinding(
                binding.uuid(),
                binding.name(),
                McpBindingStatus.INACTIVE,
                binding.baseUrl(),
                binding.transportMode(),
                binding.authorizationSecretRef(),
                binding.groupId(),
                binding.artifactId(),
                binding.version(),
                binding.artifactStatus(),
                binding.lastDiscoveredAt(),
                binding.lastDiscoveryError(),
                binding.lastContractHash(),
                binding.createdAt(),
                System.currentTimeMillis());
        bindingRepository.save(updated);
            publishMcpStatusChange(updated, previousStatus, updated.status(), null);
        log.debug("EXIT deactivate: bindingUuid={}, status={}", bindingUuid, updated.status());
        return updated;
    }

    public void delete(String bindingUuid) {
        McpBinding binding = requireBinding(bindingUuid);
        deleteSnapshots(bindingUuid);
        bindingRepository.delete(bindingUuid);
        if (attentionSignalService != null) {
            attentionSignalService.onMcpStatusChanged(binding.uuid(), binding.name(), binding.status(), McpBindingStatus.INACTIVE,
                    "Binding deleted.");
        }
    }

    public List<McpBindingTool> listTools(String bindingUuid) {
        requireBinding(bindingUuid);
        try (var stream = toolRepository.search(0, 10_000, "toolName", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            return stream.toList();
        }
    }

    public List<McpBindingResource> listResources(String bindingUuid) {
        requireBinding(bindingUuid);
        try (var stream = resourceRepository.search(0, 10_000, "name", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            return stream.toList();
        }
    }

    public List<McpBindingPrompt> listPrompts(String bindingUuid) {
        requireBinding(bindingUuid);
        try (var stream = promptRepository.search(0, 10_000, "name", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            return stream.toList();
        }
    }

    public McpBindingTool updateToolConfig(String bindingUuid,
                                           String toolId,
                                           McpToolUpdateRequest request,
                                           String username) {
        McpBinding binding = requireBinding(bindingUuid);

        McpBindingTool tool = null;
        try (var stream = toolRepository.search(0, 10_000, "toolName", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            for (McpBindingTool candidate : stream.toList()) {
                if (toolId.equals(candidate.toolId())) {
                    tool = candidate;
                    break;
                }
            }
        }

        if (tool == null) {
            throw new IllegalArgumentException("MCP tool not found: " + toolId);
        }

        Map<String, McpToolUpdateRequest.ParameterConfig> updatesByName = new HashMap<>();
        if (request != null && request.parameterConfigs() != null) {
            for (McpToolUpdateRequest.ParameterConfig param : request.parameterConfigs()) {
                if (param != null && param.name() != null && !param.name().isBlank()) {
                    updatesByName.put(param.name(), param);
                }
            }
        }

        List<McpToolParameterConfig> merged = new ArrayList<>();
        for (McpToolParameterConfig existing : tool.parameterConfigs()) {
            McpToolUpdateRequest.ParameterConfig update = updatesByName.get(existing.name());

            McpToolParameterInputMode nextInputMode = update != null && update.inputMode() != null
                    ? update.inputMode()
                    : existing.inputMode();

            String nextDefault = update != null && update.defaultValue() != null
                    ? update.defaultValue()
                    : existing.defaultValue();

            if (nextInputMode == McpToolParameterInputMode.FIXED
                    && (nextDefault == null || nextDefault.isBlank())) {
                throw new IllegalArgumentException("FIXED mode requires a non-empty default value for parameter: " + existing.name());
            }

            String nextSecretRef = existing.bindingSecretRef();
            if (nextInputMode == McpToolParameterInputMode.SECRET) {
                if (nextSecretRef == null || nextSecretRef.isBlank()) {
                    nextSecretRef = deriveParameterSecretKey(binding.name(), existing.name());
                }
                if (update != null && update.bindingSecretValue() != null && !update.bindingSecretValue().isBlank()) {
                    secureCredentialStore.saveSecretForUser(username, nextSecretRef, update.bindingSecretValue());
                }
            } else {
                nextSecretRef = "";
            }

            merged.add(new McpToolParameterConfig(
                    existing.name(),
                    existing.schemaType(),
                    existing.requiredByServer(),
                    existing.description(),
                    safe(nextDefault),
                    nextInputMode,
                    safe(nextSecretRef)
            ));
        }

        McpBindingTool updated = new McpBindingTool(
                tool.uuid(),
                tool.bindingUuid(),
                tool.toolId(),
                tool.toolName(),
                tool.description(),
                request != null && request.enabled(),
                request != null && request.requiresAuthorization(),
                merged,
                tool.schemaHash());

        toolRepository.save(updated);
        return updated;
    }

    private DiscoverySnapshot discover(McpBinding binding, String username) {
        String authHeader = loadAuthorizationHeader(username, binding.authorizationSecretRef());
        McpClient client = clientFactory.create(binding.transportMode());
        McpDiscoverResult discoverResult = client.discover(new McpClientConfig(
                binding.baseUrl(),
                binding.transportMode(),
                authHeader));
        String hash = contractHashService.sha256(discoverResult);
        return new DiscoverySnapshot(discoverResult, hash, Instant.now().toEpochMilli());
    }

        private McpDiscoverResult buildPersistedSnapshot(String bindingUuid) {
        return new McpDiscoverResult(
            listTools(bindingUuid).stream()
                .map(t -> new sh.vork.mcp.client.dto.McpDiscoveredTool(
                    t.toolId(),
                    t.toolName(),
                    t.description(),
                    t.schemaHash(),
                    t.parameterConfigs().stream()
                        .map(p -> new sh.vork.mcp.client.dto.McpDiscoveredToolParameter(
                            p.name(),
                            p.schemaType(),
                            p.requiredByServer(),
                            p.description(),
                            p.defaultValue()))
                        .toList()))
                .toList(),
            listResources(bindingUuid).stream()
                .map(r -> new sh.vork.mcp.client.dto.McpDiscoveredResource(
                    r.resourceId(),
                    r.name(),
                    r.description(),
                    r.uriTemplate(),
                    r.schemaJson()))
                .toList(),
            listPrompts(bindingUuid).stream()
                .map(p -> new sh.vork.mcp.client.dto.McpDiscoveredPrompt(
                    p.promptId(),
                    p.name(),
                    p.description(),
                    p.argumentSchemaJson()))
                .toList());
        }

    private void replaceSnapshots(String bindingUuid, McpDiscoverResult discoverResult) {
        replaceToolSnapshots(bindingUuid, discoverResult.tools());
        replaceResourceSnapshots(bindingUuid, discoverResult.resources());
        replacePromptSnapshots(bindingUuid, discoverResult.prompts());
    }

    private void replaceToolSnapshots(String bindingUuid, List<McpDiscoveredTool> discoveredTools) {
        Map<String, McpBindingTool> existingByKey = new LinkedHashMap<>();
        List<String> staleToolUuids = new ArrayList<>();

        try (var stream = toolRepository.search(0, 20_000, "toolName", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            for (McpBindingTool existing : stream.toList()) {
                String key = toolIdentityKey(existing.toolId(), existing.toolName());
                existingByKey.put(key, existing);
                staleToolUuids.add(existing.uuid());
            }
        }

        for (McpDiscoveredTool tool : discoveredTools) {
            String key = toolIdentityKey(tool.toolId(), tool.name());
            McpBindingTool existing = existingByKey.get(key);
            if (existing != null) {
                staleToolUuids.remove(existing.uuid());
            }

            List<McpToolParameterConfig> mergedParameters = mergeParameterConfigs(existing, tool.parameters());

            toolRepository.save(new McpBindingTool(
                    existing == null ? UUID.randomUUID().toString() : existing.uuid(),
                    bindingUuid,
                    safe(tool.toolId()),
                    safe(tool.name()),
                    safe(tool.description()),
                    existing == null || existing.enabled(),
                    existing != null && existing.requiresAuthorization(),
                    mergedParameters,
                    sha256String(tool.inputSchemaJson())));
        }

        staleToolUuids.forEach(toolRepository::delete);
    }

    private List<McpToolParameterConfig> mergeParameterConfigs(McpBindingTool existingTool,
                                                               List<sh.vork.mcp.client.dto.McpDiscoveredToolParameter> discovered) {
        Map<String, McpToolParameterConfig> existingByName = new HashMap<>();
        if (existingTool != null) {
            for (McpToolParameterConfig param : existingTool.parameterConfigs()) {
                existingByName.put(param.name(), param);
            }
        }

        List<McpToolParameterConfig> merged = new ArrayList<>();
        for (var p : discovered) {
            McpToolParameterConfig existing = existingByName.get(p.name());
            boolean unchangedContract = sameParameterContract(existing, p);

            McpToolParameterInputMode mode;
            String defaultValue;
            String secretRef;

            if (unchangedContract && existing != null) {
                mode = existing.inputMode();
                defaultValue = existing.defaultValue();
                secretRef = existing.bindingSecretRef();
            } else {
                mode = p.required() ? McpToolParameterInputMode.AI_REQUIRED : McpToolParameterInputMode.AI_OPTIONAL;
                defaultValue = p.defaultValue();
                secretRef = "";
            }

            merged.add(new McpToolParameterConfig(
                    p.name(),
                    p.schemaType(),
                    p.required(),
                    p.description(),
                    safe(defaultValue),
                    mode,
                    safe(secretRef)));
        }

        return merged;
    }

    private static boolean sameParameterContract(McpToolParameterConfig existing,
                                                 sh.vork.mcp.client.dto.McpDiscoveredToolParameter discovered) {
        if (existing == null || discovered == null) {
            return false;
        }
        return safe(existing.name()).equals(safe(discovered.name()))
                && safe(existing.schemaType()).equals(safe(discovered.schemaType()))
                && existing.requiredByServer() == discovered.required();
    }

    private static String toolIdentityKey(String toolId, String toolName) {
        String idPart = safe(toolId).isBlank() ? "_" : safe(toolId);
        String namePart = safe(toolName).isBlank() ? "_" : safe(toolName);
        return idPart + "|" + namePart;
    }

    private void publishMcpStatusChange(McpBinding binding,
                                        McpBindingStatus previousStatus,
                                        McpBindingStatus newStatus,
                                        String details) {
        if (attentionSignalService == null || binding == null) {
            return;
        }
        attentionSignalService.onMcpStatusChanged(binding.uuid(), binding.name(), previousStatus, newStatus, details);
    }

    private void replaceResourceSnapshots(String bindingUuid, List<McpDiscoveredResource> discoverResources) {
        List<String> staleIds = new ArrayList<>();
        try (var stream = resourceRepository.search(0, 20_000, "uuid", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            stream.forEach(r -> staleIds.add(r.uuid()));
        }

        staleIds.forEach(resourceRepository::delete);

        for (McpDiscoveredResource resource : discoverResources) {
            resourceRepository.save(new McpBindingResource(
                    UUID.randomUUID().toString(),
                    bindingUuid,
                    safe(resource.resourceId()),
                    safe(resource.name()),
                    safe(resource.description()),
                    safe(resource.uriTemplate()),
                    safe(resource.schemaJson()),
                    sha256String(resource.schemaJson() + "|" + resource.uriTemplate())));
        }
    }

    private void replacePromptSnapshots(String bindingUuid, List<McpDiscoveredPrompt> discoverPrompts) {
        List<String> staleIds = new ArrayList<>();
        try (var stream = promptRepository.search(0, 20_000, "uuid", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            stream.forEach(p -> staleIds.add(p.uuid()));
        }

        staleIds.forEach(promptRepository::delete);

        for (McpDiscoveredPrompt prompt : discoverPrompts) {
            promptRepository.save(new McpBindingPrompt(
                    UUID.randomUUID().toString(),
                    bindingUuid,
                    safe(prompt.promptId()),
                    safe(prompt.name()),
                    safe(prompt.description()),
                    safe(prompt.argumentSchemaJson()),
                    sha256String(prompt.argumentSchemaJson())));
        }
    }

    private void deleteSnapshots(String bindingUuid) {
        List<String> toolIds = new ArrayList<>();
        try (var stream = toolRepository.search(0, 20_000, "uuid", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            stream.forEach(t -> toolIds.add(t.uuid()));
        }
        toolIds.forEach(toolRepository::delete);

        List<String> resourceIds = new ArrayList<>();
        try (var stream = resourceRepository.search(0, 20_000, "uuid", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            stream.forEach(r -> resourceIds.add(r.uuid()));
        }
        resourceIds.forEach(resourceRepository::delete);

        List<String> promptIds = new ArrayList<>();
        try (var stream = promptRepository.search(0, 20_000, "uuid", SortOrder.ASC,
                SearchQuery.eq("bindingUuid", bindingUuid))) {
            stream.forEach(p -> promptIds.add(p.uuid()));
        }
        promptIds.forEach(promptRepository::delete);
    }

    private void validateNameUniqueness(String name, String existingUuid) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        McpBinding existing = bindingRepository.get(SearchQuery.eq("name", name.trim()));
        if (existing != null && (existingUuid == null || !existing.uuid().equals(existingUuid))) {
            throw new IllegalArgumentException("Binding name already exists");
        }
    }

    private McpBinding requireBinding(String bindingUuid) {
        McpBinding binding = bindingRepository.get(bindingUuid);
        if (binding == null) {
            throw new IllegalArgumentException("MCP binding not found: " + bindingUuid);
        }
        return binding;
    }

    private String saveAuthorizationSecretIfPresent(String username, String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        String secretRef = "mcp.binding.auth." + UUID.randomUUID();
        secureCredentialStore.saveSecretForUser(username, secretRef, authorization);
        return secretRef;
    }

    private String loadAuthorizationHeader(String username, String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return "";
        }
        String value = secureCredentialStore.getSecretForUser(username, secretRef);
        return value == null ? "" : value;
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "system";
        }
        return auth.getName();
    }

    private String sha256String(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to hash value", ex);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static String deriveParameterSecretKey(String bindingName, String parameterName) {
        String binding = normalizeSecretKeyPart(bindingName, "BINDING");
        String param = normalizeSecretKeyPart(parameterName, "PARAM");
        return "MCP_" + binding + "_" + param;
    }

    private static String normalizeSecretKeyPart(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String upper = value.trim().toUpperCase();
        StringBuilder sb = new StringBuilder(upper.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            boolean alphaNum = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (alphaNum) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String normalized = sb.toString();
        while (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? fallback : normalized;
    }

    public record CreateOrUpdateRequest(
            String name,
            String baseUrl,
            sh.vork.mcp.model.McpTransportMode transportMode,
            String authorization,
            String groupId,
            String artifactId,
            String version,
            ArtifactStatus artifactStatus
    ) {
    }

    public record DiscoverySnapshot(
            McpDiscoverResult discoverResult,
            String contractHash,
            long discoveredAt
    ) {
        public String asJson(ObjectMapper objectMapper) {
            try {
                return objectMapper.writeValueAsString(discoverResult.toCanonicalMap());
            } catch (JsonProcessingException ex) {
                return "{}";
            }
        }
    }

            public record SyncResult(
                DiscoverySnapshot snapshot,
                McpContractDiffService.McpContractDiff diff,
                boolean changed,
                McpBindingStatus statusAfterSync
            ) {
            }

            public record DriftInspection(
                String bindingUuid,
                String previousHash,
                String currentHash,
                    long discoveredAt,
                McpContractDiffService.McpContractDiff diff
            ) {
            }
}
