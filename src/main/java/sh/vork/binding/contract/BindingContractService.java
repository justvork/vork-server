package sh.vork.binding.contract;

import sh.vork.artifact.ArtifactStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.ReflectionInputParameter;

@Service
public class BindingContractService {

    private static final Logger log = LoggerFactory.getLogger(BindingContractService.class);
    private static final Pattern ALNUM = Pattern.compile("[a-zA-Z0-9]+$");
        private static final Set<String> SUPPORTED_INPUT_TYPES = Set.of(
            "string",
            "int",
            "double",
            "boolean",
            "date",
            "timestamp");

    private final DatabaseRepository<BindingContract> repository;

    public BindingContractService(DatabaseRepository<BindingContract> repository) {
        this.repository = repository;
    }

    public List<BindingContract> listContracts() {
        log.debug("ENTER listContracts");
        try (var stream = repository.list(0, Integer.MAX_VALUE)) {
            List<BindingContract> contracts = stream
                    .sorted(Comparator.comparing(BindingContract::uuid, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            log.debug("EXIT listContracts: count={}", contracts.size());
            return contracts;
        }
    }

    public BindingContract getContract(String id) {
        log.debug("ENTER getContract: id={}", id);
        if (id == null || id.isBlank()) {
            return null;
        }
        BindingContract contract = repository.get(id.trim());
        log.debug("EXIT getContract: found={}", contract != null);
        return contract;
    }

    public BindingContract createContract(BindingContract request) {
        log.debug("ENTER createContract: name={}", request == null ? null : request.name());
        BindingContract normalized = normalizeAndValidate(request, false);
        String vid = toVid(normalized.groupId(), normalized.artifactId(), normalized.version());
        if (repository.get(vid) != null) {
            throw new IllegalArgumentException("Binding contract already exists for deterministic VID: " + vid);
        }
        long now = System.currentTimeMillis();
        BindingContract created = new BindingContract(
                vid,
                normalized.name(),
                normalized.description(),
                normalized.tools(),
                normalized.groupId(),
                normalized.artifactId(),
                normalized.version(),
                ArtifactStatus.SNAPSHOT,
                now,
                now);
        repository.save(created);
        log.info("Binding contract created [id={}, tools={}]", created.uuid(), created.tools().size());
        log.debug("EXIT createContract: id={}", created.uuid());
        return created;
    }

    public BindingContract updateContract(String id, BindingContract request) {
        log.debug("ENTER updateContract: id={}", id);
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalizedId = id.trim();
        BindingContract existing = repository.get(normalizedId);
        if (existing == null) {
            log.debug("EXIT updateContract: not found [id={}]", id);
            return null;
        }
        if (!existing.isSnapshotMutable()) {
            throw new IllegalArgumentException("Only SNAPSHOT or REJECTED binding contracts can be edited.");
        }

        BindingContract normalized = normalizeAndValidate(request, false);
        String expectedId = toVid(normalized.groupId(), normalized.artifactId(), normalized.version());
        if (!expectedId.equals(normalizedId)) {
            throw new IllegalArgumentException("Binding contract identity is immutable and must remain: " + expectedId);
        }

        BindingContract updated = new BindingContract(
                existing.uuid(),
                normalized.name(),
                normalized.description(),
                normalized.tools(),
                existing.groupId(),
                existing.artifactId(),
                existing.version(),
                existing.artifactStatus(),
                existing.createdAt(),
                System.currentTimeMillis());
        repository.save(updated);
        log.info("Binding contract updated [id={}, tools={}]", updated.uuid(), updated.tools().size());
        log.debug("EXIT updateContract: id={}", updated.uuid());
        return updated;
    }

    public boolean deleteContract(String id) {
        log.debug("ENTER deleteContract: id={}", id);
        if (id == null || id.isBlank()) {
            return false;
        }
        String normalizedId = id.trim();
        BindingContract existing = repository.get(normalizedId);
        if (existing == null) {
            log.debug("EXIT deleteContract: not found [id={}]", id);
            return false;
        }
        if (!existing.isSnapshotMutable()) {
            throw new IllegalArgumentException("Only SNAPSHOT or REJECTED binding contracts can be deleted.");
        }
        repository.delete(normalizedId);
        log.info("Binding contract deleted [id={}]", normalizedId);
        log.debug("EXIT deleteContract: id={}", normalizedId);
        return true;
    }

    public BindingContract markSubmitted(String id) {
        return transitionStatus(id, ArtifactStatus.SUBMITTED, ArtifactStatus.SNAPSHOT, ArtifactStatus.REJECTED);
    }

    public BindingContract markStaged(String id) {
        return transitionStatus(id, ArtifactStatus.STAGED, ArtifactStatus.SUBMITTED);
    }

    public BindingContract markPublished(String id) {
        return transitionStatus(id, ArtifactStatus.PUBLISHED, ArtifactStatus.STAGED);
    }

    public BindingContractExportPackage exportContract(String id) {
        log.debug("ENTER exportContract: id={}", id);
        BindingContract contract = getContract(id);
        if (contract == null) {
            return null;
        }
        BindingContractExportPackage result = new BindingContractExportPackage(
                "vorkBindingContractExport",
                1,
                contract);
        log.debug("EXIT exportContract: id={}", id);
        return result;
    }

    public BindingContractImportResult importContract(BindingContractExportPackage pkg) {
        log.debug("ENTER importContract");
        if (pkg == null) {
            return new BindingContractImportResult("error", "Import payload is required.", null);
        }
        if (!"vorkBindingContractExport".equals(pkg.vorkBindingContractExport())) {
            return new BindingContractImportResult("error", "Not a valid Vork binding contract export package.", null);
        }
        if (pkg.contract() == null) {
            return new BindingContractImportResult("error", "Export package does not contain a contract.", null);
        }

        BindingContract normalized;
        try {
            normalized = normalizeAndValidate(pkg.contract(), true);
        } catch (IllegalArgumentException ex) {
            return new BindingContractImportResult("error", ex.getMessage(), null);
        }

        String vid = toVid(normalized.groupId(), normalized.artifactId(), normalized.version());
        String incoming = normalized.uuid();
        if (incoming != null && !incoming.isBlank() && !vid.equals(incoming)) {
            return new BindingContractImportResult(
                    "error",
                    "Incoming uuid does not match deterministic VID. Expected '" + vid + "' but got '" + incoming + "'.",
                    null);
        }

        BindingContract existing = repository.get(vid);
        long now = System.currentTimeMillis();
        BindingContract saved = new BindingContract(
                vid,
                normalized.name(),
                normalized.description(),
                normalized.tools(),
                normalized.groupId(),
                normalized.artifactId(),
                normalized.version(),
                normalized.artifactStatus(),
                existing == null ? now : existing.createdAt(),
                now);
        repository.save(saved);

        String status = existing == null ? "imported" : "updated";
        log.info("Binding contract import complete [id={}, status={}]", vid, status);
        log.debug("EXIT importContract: status={}, id={}", status, vid);
        return new BindingContractImportResult(status, null, vid);
    }

    public List<BindingContractToolDefinition> listTools(String id) {
        BindingContract contract = getRequiredContract(id);
        return contract.tools();
    }

    public BindingContract addTool(String id, BindingContractToolDefinition tool) {
        BindingContract contract = getRequiredEditableContract(id);
        BindingContractToolDefinition normalizedTool = normalizeRequiredTool(tool);
        ensureToolNameNotPresent(contract.tools(), normalizedTool.name(), null);

        List<BindingContractToolDefinition> nextTools = new ArrayList<>(contract.tools());
        nextTools.add(normalizedTool);
        BindingContract updated = withTools(contract, List.copyOf(nextTools));
        repository.save(updated);
        log.info("Binding contract tool added [id={}, tool={}]", updated.uuid(), normalizedTool.name());
        return updated;
    }

    public BindingContract updateTool(String id,
                                      String existingToolName,
                                      BindingContractToolDefinition tool) {
        BindingContract contract = getRequiredEditableContract(id);
        if (existingToolName == null || existingToolName.isBlank()) {
            throw new IllegalArgumentException("existingToolName is required.");
        }

        int index = findToolIndex(contract.tools(), existingToolName.trim());
        if (index < 0) {
            throw new IllegalArgumentException("Tool not found: " + existingToolName.trim());
        }

        BindingContractToolDefinition normalizedTool = normalizeRequiredTool(tool);
        ensureToolNameNotPresent(contract.tools(), normalizedTool.name(), index);

        List<BindingContractToolDefinition> nextTools = new ArrayList<>(contract.tools());
        nextTools.set(index, normalizedTool);
        BindingContract updated = withTools(contract, List.copyOf(nextTools));
        repository.save(updated);
        log.info("Binding contract tool updated [id={}, oldTool={}, newTool={}]",
                updated.uuid(), existingToolName.trim(), normalizedTool.name());
        return updated;
    }

    public BindingContract deleteTool(String id, String toolName) {
        BindingContract contract = getRequiredEditableContract(id);
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required.");
        }

        int index = findToolIndex(contract.tools(), toolName.trim());
        if (index < 0) {
            throw new IllegalArgumentException("Tool not found: " + toolName.trim());
        }

        List<BindingContractToolDefinition> nextTools = new ArrayList<>(contract.tools());
        BindingContractToolDefinition removed = nextTools.remove(index);
        BindingContract updated = withTools(contract, List.copyOf(nextTools));
        repository.save(updated);
        log.info("Binding contract tool deleted [id={}, tool={}]", updated.uuid(), removed.name());
        return updated;
    }

    private BindingContract transitionStatus(String id,
                                             ArtifactStatus nextStatus,
                                             ArtifactStatus... allowedCurrentStatuses) {
        log.debug("ENTER transitionStatus: id={}, next={}", id, nextStatus);
        if (id == null || id.isBlank()) {
            return null;
        }
        BindingContract existing = repository.get(id.trim());
        if (existing == null) {
            log.debug("EXIT transitionStatus: not found [id={}]", id);
            return null;
        }
        Set<ArtifactStatus> allowed = Set.of(allowedCurrentStatuses);
        if (!allowed.contains(existing.artifactStatus())) {
            throw new IllegalArgumentException("Invalid lifecycle transition from "
                    + existing.artifactStatus() + " to " + nextStatus + ".");
        }

        BindingContract updated = new BindingContract(
                existing.uuid(),
                existing.name(),
                existing.description(),
                existing.tools(),
                existing.groupId(),
                existing.artifactId(),
                existing.version(),
                nextStatus,
                existing.createdAt(),
                System.currentTimeMillis());
        repository.save(updated);
        log.info("Binding contract status updated [id={}, from={}, to={}]",
                existing.uuid(), existing.artifactStatus(), nextStatus);
        log.debug("EXIT transitionStatus: id={}, next={}", existing.uuid(), nextStatus);
        return updated;
    }

    private BindingContract normalizeAndValidate(BindingContract request, boolean requireAtLeastOneTool) {
        if (request == null) {
            throw new IllegalArgumentException("Binding contract payload is required.");
        }

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }

        String groupId = request.groupId() == null ? "" : request.groupId().trim();
        String artifactId = request.artifactId() == null ? "" : request.artifactId().trim();
        String version = request.version() == null ? "" : request.version().trim();
        String identityError = validateArtifactIdentity(groupId, artifactId, version);
        if (identityError != null) {
            throw new IllegalArgumentException(identityError);
        }

        List<BindingContractToolDefinition> tools = normalizeTools(request.tools());
        if (requireAtLeastOneTool && tools.isEmpty()) {
            throw new IllegalArgumentException("At least one tool definition is required.");
        }

        ArtifactStatus artifactStatus = request.artifactStatus() == null
                ? ArtifactStatus.SNAPSHOT
                : request.artifactStatus();

        return new BindingContract(
                request.uuid(),
                name,
                request.description() == null ? "" : request.description().trim(),
                tools,
                groupId,
                artifactId,
                version,
                artifactStatus,
                request.createdAt(),
                request.updatedAt());
    }

    private BindingContract getRequiredContract(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required.");
        }
        BindingContract contract = repository.get(id.trim());
        if (contract == null) {
            throw new IllegalArgumentException("Binding contract not found: " + id.trim());
        }
        return contract;
    }

    private BindingContract getRequiredEditableContract(String id) {
        BindingContract contract = getRequiredContract(id);
        if (!contract.isSnapshotMutable()) {
            throw new IllegalArgumentException("Only SNAPSHOT or REJECTED binding contracts can be edited.");
        }
        return contract;
    }

    private static BindingContractToolDefinition normalizeRequiredTool(BindingContractToolDefinition tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("Tool name is required.");
        }
        List<BindingContractToolDefinition> normalized = normalizeTools(List.of(tool));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Tool name is required.");
        }
        return normalized.getFirst();
    }

    private static int findToolIndex(List<BindingContractToolDefinition> tools, String toolName) {
        for (int i = 0; i < tools.size(); i++) {
            BindingContractToolDefinition tool = tools.get(i);
            if (tool.name().equalsIgnoreCase(toolName)) {
                return i;
            }
        }
        return -1;
    }

    private static void ensureToolNameNotPresent(List<BindingContractToolDefinition> tools,
                                                 String candidateName,
                                                 Integer exceptIndex) {
        for (int i = 0; i < tools.size(); i++) {
            if (exceptIndex != null && i == exceptIndex) {
                continue;
            }
            if (tools.get(i).name().equalsIgnoreCase(candidateName)) {
                throw new IllegalArgumentException("Tool names must be unique. Duplicate: " + candidateName);
            }
        }
    }

    private static BindingContract withTools(BindingContract source, List<BindingContractToolDefinition> tools) {
        return new BindingContract(
                source.uuid(),
                source.name(),
                source.description(),
                tools,
                source.groupId(),
                source.artifactId(),
                source.version(),
                source.artifactStatus(),
                source.createdAt(),
                System.currentTimeMillis());
    }

    private static List<BindingContractToolDefinition> normalizeTools(List<BindingContractToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        List<BindingContractToolDefinition> normalized = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (BindingContractToolDefinition tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            String name = tool.name().trim();
            String key = name.toLowerCase(Locale.ROOT);
            if (!names.add(key)) {
                throw new IllegalArgumentException("Tool names must be unique. Duplicate: " + name);
            }

            List<ReflectionInputParameter> params = normalizeInputParameters(tool.inputParameters());
            normalized.add(new BindingContractToolDefinition(
                    name,
                    tool.description() == null ? "" : tool.description().trim(),
                    params,
                    tool.publiclyVisible() == null || tool.publiclyVisible()));
        }
        return List.copyOf(normalized);
    }

    private static List<ReflectionInputParameter> normalizeInputParameters(List<ReflectionInputParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        List<ReflectionInputParameter> normalized = new ArrayList<>();
        for (ReflectionInputParameter parameter : parameters) {
            if (parameter == null || parameter.name() == null || parameter.name().isBlank()) {
                continue;
            }
            String type = parameter.type() == null || parameter.type().isBlank()
                    ? "string"
                    : parameter.type().trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_INPUT_TYPES.contains(type)) {
                throw new IllegalArgumentException("Unsupported input parameter type: " + type);
            }
            normalized.add(new ReflectionInputParameter(
                    parameter.name().trim(),
                    type,
                    parameter.description() == null ? "" : parameter.description().trim(),
                    parameter.required(),
                    parameter.array()));
        }
        return List.copyOf(normalized);
    }

    private static String validateArtifactIdentity(String groupId, String artifactId, String version) {
        if (groupId == null || groupId.isBlank()) {
            return "groupId is required.";
        }
        if (artifactId == null || artifactId.isBlank()) {
            return "artifactId is required.";
        }
        if (groupId.length() < 3 || groupId.length() > 64) {
            return "groupId length must be between 3 and 64 characters.";
        }
        if (artifactId.length() < 3 || artifactId.length() > 64) {
            return "artifactId length must be between 3 and 64 characters.";
        }
        if (!ALNUM.matcher(groupId).matches()) {
            return "groupId must be alphanumeric only.";
        }
        if (!ALNUM.matcher(artifactId).matches()) {
            return "artifactId must be alphanumeric only.";
        }
        if (version == null || version.isBlank()) {
            return "version is required.";
        }
        if (version.length() > 16) {
            return "version length must be 16 characters or fewer.";
        }
        if (!"SNAPSHOT".equals(version)) {
            return "Only version SNAPSHOT is supported in this flow.";
        }
        return null;
    }

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }

    public record BindingContractExportPackage(
            String vorkBindingContractExport,
            int version,
            BindingContract contract
    ) {}

    public record BindingContractImportResult(
            String status,
            String message,
            String contractUuid
    ) {}
}
