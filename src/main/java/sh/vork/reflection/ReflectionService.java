package sh.vork.reflection;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sh.vork.ai.security.SkillSecretSubstitutor;
import sh.vork.ai.function.OAuthConnectRequest;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.oauth.OAuthTemplate;
import sh.vork.oauth.OAuthClientService;
import sh.vork.oauth.OAuthTemplateService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SortOrder;
import sh.vork.security.SecureCredentialStore;
import sh.vork.skill.SkillSecret;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.TypeRecordBindingScope;
import sh.vork.typegen.TypeRecordVersionMetadata;
import sh.vork.util.ToolIdGenerator;
import sh.vork.web.RequestOriginContext;

@Service
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);

    private static final Pattern REFLECTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern IDENTITY_PATTERN = Pattern.compile("^[A-Za-z0-9]{3,64}$");
    private static final Pattern BINDING_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern TEMPLATE_TOKEN_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9._-]+)\\s*\\}\\}");
    private static final Set<String> METHODS_WITHOUT_BODY = Set.of("GET", "DELETE", "HEAD", "OPTIONS");
    private static final String MANDATORY_RECORD_TOOL_MARKER = "x-vork-mandatory-record-tool";
        private static final String CONTENT_TYPE_JSON = "application/json";
        private static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded";
        private static final String CONTENT_TYPE_TEXT = "text/plain";
        private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            CONTENT_TYPE_JSON,
            CONTENT_TYPE_FORM,
            CONTENT_TYPE_TEXT);
    private static final Set<String> SUPPORTED_BINDING_PARAMETER_TYPES = Set.of(
            "string",
            "int",
            "double",
            "boolean",
            "hidden");
    private static final long PENDING_OAUTH_BINDING_TTL_MS = 15 * 60 * 1000L;
    private static final boolean DEV_UNREDACTED_LOGS = resolveDevUnredactedLogsFlag();

    private final DatabaseRepository<Reflection> reflectionRepository;
    private final DatabaseRepository<RecordReflection> recordReflectionRepository;
    private final DatabaseRepository<ReflectionGroup> reflectionGroupRepository;
    private final DatabaseRepository<ReflectionBinding> reflectionBindingRepository;
    private final DatabaseRepository<PendingOAuthBindingAction> pendingOAuthBindingActionRepository;
    private final OAuthClientService oauthClientService;
    private final OAuthTemplateService oauthTemplateService;
    private final SkillSecretSubstitutor skillSecretSubstitutor;
    private final SecureCredentialStore secureCredentialStore;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private TypeDatabaseService typeDatabaseService;
    private JavaTypeClassLoader javaTypeClassLoader;
    private DatabaseRepository<TypeRecordBindingScope> typeRecordBindingScopeRepository;
    private DatabaseRepository<TypeRecordVersionMetadata> typeRecordVersionMetadataRepository;
    private DatabaseRepository<JavaType> javaTypeRepository;

    @Autowired
    public ReflectionService(RepositoryFactory factory,
                             OAuthClientService oauthClientService,
                     OAuthTemplateService oauthTemplateService,
                             SkillSecretSubstitutor skillSecretSubstitutor,
                             SecureCredentialStore secureCredentialStore,
                             ObjectMapper objectMapper) {
        this(
                factory.create(Reflection.class),
                factory.create(RecordReflection.class),
                factory.create(ReflectionGroup.class),
                factory.create(ReflectionBinding.class),
                factory.create(PendingOAuthBindingAction.class),
                oauthClientService,
            oauthTemplateService,
                skillSecretSubstitutor,
                secureCredentialStore,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    @Autowired(required = false)
    public void setTypeDatabaseService(TypeDatabaseService typeDatabaseService) {
        this.typeDatabaseService = typeDatabaseService;
    }

    @Autowired(required = false)
    public void setJavaTypeClassLoader(JavaTypeClassLoader javaTypeClassLoader) {
        this.javaTypeClassLoader = javaTypeClassLoader;
    }

    @Autowired(required = false)
    public void setTypeRecordBindingScopeRepository(DatabaseRepository<TypeRecordBindingScope> typeRecordBindingScopeRepository) {
        this.typeRecordBindingScopeRepository = typeRecordBindingScopeRepository;
    }

    @Autowired(required = false)
    public void setTypeRecordVersionMetadataRepository(DatabaseRepository<TypeRecordVersionMetadata> typeRecordVersionMetadataRepository) {
        this.typeRecordVersionMetadataRepository = typeRecordVersionMetadataRepository;
    }

    @Autowired(required = false)
    public void setJavaTypeRepository(DatabaseRepository<JavaType> javaTypeRepository) {
        this.javaTypeRepository = javaTypeRepository;
    }

        ReflectionService(DatabaseRepository<Reflection> reflectionRepository,
                  DatabaseRepository<RecordReflection> recordReflectionRepository,
                  DatabaseRepository<ReflectionGroup> reflectionGroupRepository,
                  DatabaseRepository<ReflectionBinding> reflectionBindingRepository,
                      DatabaseRepository<PendingOAuthBindingAction> pendingOAuthBindingActionRepository,
                  OAuthClientService oauthClientService,
                  SkillSecretSubstitutor skillSecretSubstitutor,
                  SecureCredentialStore secureCredentialStore,
                  ObjectMapper objectMapper,
                  HttpClient httpClient) {
        this(
            reflectionRepository,
            recordReflectionRepository,
            reflectionGroupRepository,
            reflectionBindingRepository,
            pendingOAuthBindingActionRepository,
            oauthClientService,
            null,
            skillSecretSubstitutor,
            secureCredentialStore,
            objectMapper,
            httpClient);
        }

    ReflectionService(DatabaseRepository<Reflection> reflectionRepository,
                      DatabaseRepository<RecordReflection> recordReflectionRepository,
                      DatabaseRepository<ReflectionGroup> reflectionGroupRepository,
                      DatabaseRepository<ReflectionBinding> reflectionBindingRepository,
                      DatabaseRepository<PendingOAuthBindingAction> pendingOAuthBindingActionRepository,
                      OAuthClientService oauthClientService,
                  OAuthTemplateService oauthTemplateService,
                      SkillSecretSubstitutor skillSecretSubstitutor,
                      SecureCredentialStore secureCredentialStore,
                      ObjectMapper objectMapper,
                      HttpClient httpClient) {
        this.reflectionRepository = reflectionRepository;
        this.recordReflectionRepository = recordReflectionRepository;
        this.reflectionGroupRepository = reflectionGroupRepository;
        this.reflectionBindingRepository = reflectionBindingRepository;
        this.pendingOAuthBindingActionRepository = pendingOAuthBindingActionRepository;
        this.oauthClientService = oauthClientService;
        this.oauthTemplateService = oauthTemplateService;
        this.skillSecretSubstitutor = skillSecretSubstitutor;
        this.secureCredentialStore = secureCredentialStore;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        if (DEV_UNREDACTED_LOGS) {
            log.warn("Developer unredacted logging mode is ENABLED for ReflectionService. Sensitive values (headers, tokens, body fields) may be written to logs.");
        }
    }

    public List<ReflectionGroup> listGroups() {
        log.debug("ENTER listGroups");
        try (var stream = reflectionGroupRepository.list(0, Integer.MAX_VALUE)) {
            return stream.sorted(Comparator.comparing(
                    group -> group.name() == null ? "" : group.name(),
                    String.CASE_INSENSITIVE_ORDER)).toList();
        }
    }

    public ReflectionGroup getGroup(String uuid) {
        log.debug("ENTER getGroup: [uuid={}]", uuid);
        return reflectionGroupRepository.get(uuid);
    }

    public ReflectionGroup getGroupByToolId(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        String normalized = ToolIdGenerator.normalizeBase(toolId, "group");
        try (var stream = reflectionGroupRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(group -> normalized.equals(ToolIdGenerator.normalizeBase(group.toolId(), "group")))
                    .findFirst()
                    .orElse(null);
        }
    }

    public ReflectionGroup createGroup(ReflectionGroupRequest request) {
        log.debug("ENTER createGroup: [name={}]", request == null ? "null" : request.name());
        if (request == null) {
            throw new IllegalArgumentException("Group payload is required.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Group name is required.");
        }
        ReflectionType type = parseGroupType(request.type());
        ReflectionAuthenticationMode authenticationMode = parseAuthenticationMode(request.authenticationMode());
        String oauthTemplateId = normalizeAndValidateOAuthSettings(type, authenticationMode, request.oauthTemplateId());
        String groupId = identityOrDefault(request.groupId(), request.name(), "group");
        String artifactId = identityOrDefault(request.artifactId(), request.name(), "reflectiongroup");
        String version = "SNAPSHOT";
        String uuid = toVid(groupId, artifactId, version);
        if (reflectionGroupRepository.get(uuid) != null) {
            throw new IllegalArgumentException("Reflection group already exists: " + uuid);
        }
        String toolId = uniqueGroupToolId(request.name(), null);
        long now = System.currentTimeMillis();
        ReflectionGroup group = new ReflectionGroup(
                uuid,
            toolId,
                request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                type,
            request.baseUrl() == null ? "" : request.baseUrl().trim(),
            request.urlOverrideEnabled(),
            normalizeBindingSecrets(request.bindingSecrets()),
            normalizeBindingParameters(request.bindingParameters()),
                authenticationMode,
                oauthTemplateId,
                groupId,
                artifactId,
                version,
                ArtifactStatus.SNAPSHOT,
                now,
                now);
        reflectionGroupRepository.save(group);
        log.info("Reflection group created [uuid={}, name={}, type={}]", group.uuid(), group.name(), group.type());
        return group;
    }

    public ReflectionGroup updateGroup(String uuid, ReflectionGroupRequest request) {
        log.debug("ENTER updateGroup: [uuid={}]", uuid);
        ReflectionGroup existing = reflectionGroupRepository.get(uuid);
        if (existing == null) {
            return null;
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Group name is required.");
        }
        ReflectionType type = parseGroupType(request.type());
        ReflectionAuthenticationMode authenticationMode = parseAuthenticationMode(request.authenticationMode());
        String oauthTemplateId = normalizeAndValidateOAuthSettings(type, authenticationMode, request.oauthTemplateId());
        String toolId = uniqueGroupToolId(request.name(), existing.uuid());
        ReflectionGroup updated = new ReflectionGroup(
                existing.uuid(),
            toolId,
                request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                type,
            request.baseUrl() == null ? "" : request.baseUrl().trim(),
            request.urlOverrideEnabled(),
            normalizeBindingSecrets(request.bindingSecrets()),
            normalizeBindingParameters(request.bindingParameters()),
                authenticationMode,
                oauthTemplateId,
                existing.groupId(),
                existing.artifactId(),
                existing.version(),
                existing.artifactStatus(),
                existing.createdAt(),
                System.currentTimeMillis());
        reflectionGroupRepository.save(updated);
        log.info("Reflection group updated [uuid={}, name={}, type={}, version={}]",
                updated.uuid(), updated.name(), updated.type(), updated.version());
        return updated;
    }

    public GroupDeleteResult deleteGroup(String uuid) {
        log.debug("ENTER deleteGroup: [uuid={}]", uuid);
        ReflectionGroup existing = reflectionGroupRepository.get(uuid);
        if (existing == null) {
            return new GroupDeleteResult(false, "Group not found.");
        }
        List<Reflection> members = reflectionsForGroup(uuid);
        if (!members.isEmpty()) {
            return new GroupDeleteResult(false, "Cannot delete non-empty group. Remove reflections first.");
        }
        List<ReflectionBinding> bindings = bindingsForGroup(uuid);
        if (!bindings.isEmpty()) {
            return new GroupDeleteResult(false, "Cannot delete group with bindings. Remove bindings first.");
        }
        reflectionGroupRepository.delete(uuid);
        log.info("Reflection group deleted [uuid={}]", uuid);
        return new GroupDeleteResult(true, null);
    }

    public List<ReflectionBinding> bindingsForGroup(String groupUuid) {
        if (groupUuid == null || groupUuid.isBlank()) {
            return List.of();
        }
        try (var stream = reflectionBindingRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(binding -> {
                        ReflectionGroup bindingGroup = getBindingGroup(binding);
                        return bindingGroup != null && groupUuid.equals(bindingGroup.uuid());
                    })
                    .sorted(Comparator.comparing(
                            binding -> binding.name() == null ? "" : binding.name(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public ReflectionBinding getBinding(String groupUuid, String bindingName) {
        if (groupUuid == null || groupUuid.isBlank() || bindingName == null || bindingName.isBlank()) {
            return null;
        }
        return bindingsForGroup(groupUuid).stream()
                .filter(binding -> bindingName.equalsIgnoreCase(binding.name()))
                .findFirst()
                .orElse(null);
    }

    public ReflectionBinding getBindingByUuid(String bindingUuid) {
        if (bindingUuid == null || bindingUuid.isBlank()) {
            return null;
        }
        return reflectionBindingRepository.get(bindingUuid);
    }

    public Reflection getBindingReflection(ReflectionBinding binding) {
        if (binding == null || binding.reflectionUuid() == null || binding.reflectionUuid().isBlank()) {
            return null;
        }
        return getReflection(binding.reflectionUuid());
    }

    public ReflectionGroup getBindingGroup(ReflectionBinding binding) {
        Reflection reflection = getBindingReflection(binding);
        if (reflection == null || reflection.groupUuid() == null || reflection.groupUuid().isBlank()) {
            if (binding == null || binding.reflectionUuid() == null || binding.reflectionUuid().isBlank()) {
                return null;
            }
            // Transitional fallback for pre-migration data that stored a group UUID here.
            return reflectionGroupRepository.get(binding.reflectionUuid());
        }
        return reflectionGroupRepository.get(reflection.groupUuid());
    }

    public ReflectionBinding createBinding(String username, String groupUuid, ReflectionBindingRequest request) {
        ReflectionGroup group = requireGroup(groupUuid);
        requireUsername(username);
        ReflectionBinding normalized = normalizeBindingRequest(null, group, request);
        enforceDefaultBindingInvariant(groupUuid, null, normalized.name());
        requireConnectedBindingOAuthProfile(username, group, normalized.name());
        reflectionBindingRepository.save(normalized);
        saveBindingSecrets(
                username,
                normalized,
                group.bindingSecrets(),
                mergeSecretValuesFromSourceBinding(username, groupUuid, request));
        log.info("Reflection binding created [groupUuid={}, name={}]", groupUuid, normalized.name());
        return normalized;
    }

    public BindingSaveOutcome saveBindingWithOAuthFlow(String username,
                                                       String groupUuid,
                                                       String originalBindingName,
                                                       ReflectionBindingRequest request) {
        return saveBindingWithOAuthFlow(username, groupUuid, originalBindingName, request, null, null, null);
    }

    public BindingSaveOutcome saveBindingWithOAuthFlow(String username,
                                                       String groupUuid,
                                                       String originalBindingName,
                                                       ReflectionBindingRequest request,
                                                       String clientId,
                                                       String clientSecret,
                                                       String redirectUri) {
        requireUsername(username);
        ReflectionGroup group = requireGroup(groupUuid);

        boolean update = originalBindingName != null && !originalBindingName.isBlank();
        if (group.authenticationMode() != ReflectionAuthenticationMode.OAUTH) {
            if (update) {
                ReflectionBinding updated = updateBinding(username, groupUuid, originalBindingName, request);
                if (updated == null) {
                    return new BindingSaveOutcome("not_found", "Binding not found.", null, null);
                }
                return new BindingSaveOutcome("binding_saved", "Binding updated.", null, updated);
            }
            ReflectionBinding created = createBinding(username, groupUuid, request);
            return new BindingSaveOutcome("binding_saved", "Binding created.", null, created);
        }

        ReflectionBinding existing = null;
        if (update) {
            existing = getBinding(groupUuid, originalBindingName);
            if (existing == null) {
                return new BindingSaveOutcome("not_found", "Binding not found.", null, null);
            }
        }

        ReflectionBinding normalized = normalizeBindingRequest(existing, group, request);
        enforceDefaultBindingInvariant(groupUuid, existing == null ? null : existing.name(), normalized.name());

        OAuthTemplate template = resolveOAuthTemplate(group.oauthTemplateId());
        String profileName = OAuthClientService.normalizeProfileName(normalized.name());

        String effectiveRedirectUri = redirectUri == null ? "" : redirectUri.trim();
        if (effectiveRedirectUri.isBlank()) {
            String baseUrl = RequestOriginContext.resolveBaseUrlFromCurrentRequest();
            if (baseUrl != null && !baseUrl.isBlank()) {
                effectiveRedirectUri = baseUrl + "/api/oauth/callback";
            }
        }

        Map<String, Object> connectResult = oauthClientService.connectOrEnsure(username, new OAuthConnectRequest(
                template.clientName(),
                profileName,
                template.authorizeEndpoint() == null ? null : template.authorizeEndpoint().toString(),
                template.tokenEndpoint() == null ? null : template.tokenEndpoint().toString(),
                normalizeOptional(clientId),
                normalizeOptional(clientSecret),
                normalizeOptional(effectiveRedirectUri),
                template.scopes(),
                template.authorizationParameters(),
                false,
                "/reflections"));

        String connectStatus = String.valueOf(connectResult.getOrDefault("status", ""));
        if ("ready".equals(connectStatus)) {
            if (update) {
                ReflectionBinding updated = updateBinding(username, groupUuid, originalBindingName, request);
                if (updated == null) {
                    return new BindingSaveOutcome("not_found", "Binding not found.", null, null);
                }
                return new BindingSaveOutcome("binding_saved", "Binding updated.", null, updated);
            }
            ReflectionBinding created = createBinding(username, groupUuid, request);
            return new BindingSaveOutcome("binding_saved", "Binding created.", null, created);
        }

        if ("connect_required".equals(connectStatus)) {
            if (pendingOAuthBindingActionRepository == null) {
                return new BindingSaveOutcome("error", "OAuth pending action storage is unavailable.", null, null);
            }
            String state = String.valueOf(connectResult.getOrDefault("state", ""));
            String authorizationUrl = String.valueOf(connectResult.getOrDefault("authorizationUrl", ""));
            if (state.isBlank() || authorizationUrl.isBlank()) {
                return new BindingSaveOutcome("error", "OAuth connect flow could not be started.", null, null);
            }
            long now = System.currentTimeMillis();
            pendingOAuthBindingActionRepository.save(new PendingOAuthBindingAction(
                    state,
                    username,
                    groupUuid,
                    update ? originalBindingName : "",
                    normalized.name(),
                    normalized.baseUrl(),
                    normalized.parameterValues(),
                    mergeSecretValuesFromSourceBinding(username, groupUuid, request),
                    request.copySecretsFromBindingName(),
                    now,
                    now + PENDING_OAUTH_BINDING_TTL_MS));
            return new BindingSaveOutcome(
                    "connect_required",
                    "Complete OAuth consent to finish saving the binding.",
                    authorizationUrl,
                    null);
        }

        String message = String.valueOf(connectResult.getOrDefault("message", "OAuth flow failed."));
        return new BindingSaveOutcome("error", message, null, null);
    }

    private static String normalizeOptional(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isBlank() ? null : value;
    }

    public PendingOAuthBindingCompletion completePendingOAuthBinding(String oauthState) {
        if (oauthState == null || oauthState.isBlank() || pendingOAuthBindingActionRepository == null) {
            return PendingOAuthBindingCompletion.notHandled();
        }

        PendingOAuthBindingAction pending = pendingOAuthBindingActionRepository.get(oauthState);
        if (pending == null) {
            return PendingOAuthBindingCompletion.notHandled();
        }

        if (pending.expiresAt() > 0 && pending.expiresAt() < System.currentTimeMillis()) {
            pendingOAuthBindingActionRepository.delete(oauthState);
            return PendingOAuthBindingCompletion.failed(
                    pending.groupUuid(),
                    pending.bindingName(),
                    "OAuth approval expired before binding could be saved.");
        }

        try {
            ReflectionBindingRequest request = new ReflectionBindingRequest(
                    pending.bindingName(),
                    null,
                    pending.baseUrl(),
                    pending.parameterValues(),
                    pending.secretValues(),
                    pending.copySecretsFromBindingName());

            ReflectionBinding saved;
            if (pending.originalBindingName() == null || pending.originalBindingName().isBlank()) {
                saved = createBinding(pending.userUuid(), pending.groupUuid(), request);
            } else {
                saved = updateBinding(pending.userUuid(), pending.groupUuid(), pending.originalBindingName(), request);
                if (saved == null) {
                    pendingOAuthBindingActionRepository.delete(oauthState);
                    return PendingOAuthBindingCompletion.failed(
                            pending.groupUuid(),
                            pending.bindingName(),
                            "Binding not found for OAuth completion.");
                }
            }

            pendingOAuthBindingActionRepository.delete(oauthState);
            return PendingOAuthBindingCompletion.succeeded(pending.groupUuid(), saved.name());
        } catch (IllegalArgumentException ex) {
            pendingOAuthBindingActionRepository.delete(oauthState);
            return PendingOAuthBindingCompletion.failed(pending.groupUuid(), pending.bindingName(), ex.getMessage());
        }
    }

    public ReflectionBinding updateBinding(String username,
                                           String groupUuid,
                                           String bindingName,
                                           ReflectionBindingRequest request) {
        ReflectionGroup group = requireGroup(groupUuid);
        requireUsername(username);
        ReflectionBinding existing = getBinding(groupUuid, bindingName);
        if (existing == null) {
            return null;
        }
        ReflectionBinding normalized = normalizeBindingRequest(existing, group, request);
        enforceDefaultBindingInvariant(groupUuid, existing.name(), normalized.name());
        requireConnectedBindingOAuthProfile(username, group, normalized.name());
        reflectionBindingRepository.save(normalized);
        saveBindingSecrets(username, normalized, group.bindingSecrets(), request.secretValues());
        log.info("Reflection binding updated [groupUuid={}, name={}]", groupUuid, normalized.name());
        return normalized;
    }

    public boolean deleteBinding(String username, String groupUuid, String bindingName) {
        requireUsername(username);
        ReflectionGroup group = requireGroup(groupUuid);
        ReflectionBinding existing = getBinding(groupUuid, bindingName);
        if (existing == null) {
            return false;
        }
        reflectionBindingRepository.delete(existing.uuid());
        clearBindingSecrets(username, existing);
        clearBindingOAuthProfile(username, group, existing.name());
        log.info("Reflection binding deleted [groupUuid={}, name={}]", groupUuid, bindingName);
        return true;
    }

    public List<Reflection> listReflections() {
        log.debug("ENTER listReflections");
        List<Reflection> merged = new ArrayList<>();
        try (var stream = reflectionRepository.list(0, Integer.MAX_VALUE)) {
            merged.addAll(stream.toList());
        }
        try (var stream = recordReflectionRepository.list(0, Integer.MAX_VALUE)) {
            stream.map(this::recordToReflection).forEach(merged::add);
        }
        return merged.stream()
                .sorted(Comparator.comparing(
                        reflection -> reflection.id() == null ? "" : reflection.id(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<Reflection> reflectionsForGroup(String groupUuid) {
        if (groupUuid == null || groupUuid.isBlank()) {
            return List.of();
        }
        return listReflections().stream()
                .filter(reflection -> groupUuid.equals(reflection.groupUuid()))
                .toList();
    }

    public Reflection getReflection(String uuid) {
        Reflection reflection = reflectionRepository.get(uuid);
        if (reflection != null) {
            return reflection;
        }
        RecordReflection recordReflection = recordReflectionRepository.get(uuid);
        return recordReflection == null ? null : recordToReflection(recordReflection);
    }

    public Reflection getReflectionById(String reflectionId) {
        if (reflectionId == null || reflectionId.isBlank()) {
            return null;
        }
        return listReflections().stream()
                .filter(reflection -> reflectionId.equals(reflection.id()))
                .findFirst()
                .orElse(null);
    }

    public Reflection createReflection(ReflectionRequest request) {
        log.debug("ENTER createReflection: [id={}]", request == null ? "null" : request.id());
        Reflection normalized = normalizeAndValidate(null, request);
        reflectionRepository.save(normalized);
        log.info("Reflection created [uuid={}, id={}]", normalized.uuid(), normalized.id());
        return normalized;
    }

        public void ensureRecordReflectionsForType(String recordFqn) {
        log.debug("ENTER ensureRecordReflectionsForType: [recordFqn={}]", recordFqn);
        if (recordFqn == null || recordFqn.isBlank()) {
            throw new IllegalArgumentException("recordFqn is required.");
        }

        String normalizedFqn = recordFqn.trim();
        String simpleName = normalizedFqn;
        int lastDot = normalizedFqn.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < normalizedFqn.length() - 1) {
            simpleName = normalizedFqn.substring(lastDot + 1);
        }

        String hashSuffix = Long.toString(Integer.toUnsignedLong(normalizedFqn.hashCode()), 36).toUpperCase(Locale.ROOT);
        String compactSimpleName = simpleName.replaceAll("[^A-Za-z0-9]", "");
        if (compactSimpleName.isBlank()) {
            compactSimpleName = "RecordType";
        }

        String groupId = identityOrDefault("Record" + hashSuffix, simpleName, "group");
        String artifactId = identityOrDefault(compactSimpleName + hashSuffix, simpleName, "recordartifact");
        String groupUuid = toVid(groupId, artifactId, "SNAPSHOT");

        long now = System.currentTimeMillis();
        ReflectionGroup group = reflectionGroupRepository.get(groupUuid);
        if (group == null) {
            group = new ReflectionGroup(
                groupUuid,
                uniqueGroupToolId("record" + compactSimpleName, null),
                "Record " + simpleName,
                "System-managed record reflections for " + normalizedFqn,
                ReflectionType.RECORD,
                "",
                true,
                List.of(),
                List.of(),
                ReflectionAuthenticationMode.NONE,
                "",
                groupId,
                artifactId,
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT,
                now,
                now);
            reflectionGroupRepository.save(group);
            log.info("Record reflection group created [groupUuid={}, fqn={}]", groupUuid, normalizedFqn);
        } else if (group.type() != ReflectionType.RECORD) {
            throw new IllegalArgumentException("Existing reflection group is not of type RECORD: " + groupUuid);
        }

        upsertMandatoryRecordToolReflection(
            group,
            normalizedFqn,
            simpleName,
            "CREATE",
            "Create " + simpleName,
            "Create or update a " + simpleName + " record.",
            List.of(new ReflectionInputParameter("uuid", "string", "Record UUID.", true)));

        upsertMandatoryRecordToolReflection(
            group,
            normalizedFqn,
            simpleName,
            "READ",
            "Get " + simpleName,
            "Read a " + simpleName + " record by UUID.",
            List.of(new ReflectionInputParameter("uuid", "string", "Record UUID.", true)));

        upsertMandatoryRecordToolReflection(
            group,
            normalizedFqn,
            simpleName,
            "UPDATE",
            "Update " + simpleName,
            "Update or upsert a " + simpleName + " record.",
            List.of(new ReflectionInputParameter("uuid", "string", "Record UUID.", true)));

        upsertMandatoryRecordToolReflection(
            group,
            normalizedFqn,
            simpleName,
            "DELETE",
            "Delete " + simpleName,
            "Delete a " + simpleName + " record by UUID.",
            List.of(new ReflectionInputParameter("uuid", "string", "Record UUID.", true)));

        upsertMandatoryRecordToolReflection(
            group,
            normalizedFqn,
            simpleName,
            "SEARCH",
            "Search " + simpleName,
            "Search " + simpleName + " records using SQL-like query syntax.",
            List.of(
                new ReflectionInputParameter("query", "string", "SQL-like query string.", true),
                new ReflectionInputParameter("queryType", "string", "Query parser mode (SQL or MONGO).", false),
                new ReflectionInputParameter("sortField", "string", "Sort field.", false),
                new ReflectionInputParameter("sortOrder", "string", "Sort direction (ASC or DESC).", false),
                new ReflectionInputParameter("page", "int", "Page number.", false),
                new ReflectionInputParameter("pageSize", "int", "Page size.", false)));

        log.info("Record reflections ensured [groupUuid={}, fqn={}]", groupUuid, normalizedFqn);
        }

    public Reflection updateReflection(String uuid, ReflectionRequest request) {
        log.debug("ENTER updateReflection: [uuid={}]", uuid);
        Reflection existing = reflectionRepository.get(uuid);
        if (existing == null) {
            RecordReflection recordExisting = recordReflectionRepository.get(uuid);
            if (recordExisting != null) {
                throw new IllegalArgumentException("Mandatory record reflection tools are immutable and cannot be updated.");
            }
            return null;
        }
        if (isMandatoryRecordTool(existing)) {
            throw new IllegalArgumentException("Mandatory record reflection tools are immutable and cannot be updated.");
        }
        Reflection normalized = normalizeAndValidate(existing, request);
        reflectionRepository.save(normalized);
        log.info("Reflection updated [uuid={}, id={}, version={}]",
                normalized.uuid(), normalized.id(), normalized.version());
        return normalized;
    }

    public boolean deleteReflection(String uuid) {
        Reflection existing = reflectionRepository.get(uuid);
        if (existing == null) {
            RecordReflection recordExisting = recordReflectionRepository.get(uuid);
            if (recordExisting != null) {
                throw new IllegalArgumentException("Mandatory record reflection tools are immutable and cannot be deleted.");
            }
            return false;
        }
        if (isMandatoryRecordTool(existing)) {
            throw new IllegalArgumentException("Mandatory record reflection tools are immutable and cannot be deleted.");
        }
        reflectionRepository.delete(uuid);
        log.info("Reflection deleted [uuid={}, id={}]", uuid, existing.id());
        return true;
    }

    public ReflectionGroupExportPackage exportGroup(String groupUuid) {
        log.debug("ENTER exportGroup: [groupUuid={}]", groupUuid);
        ReflectionGroup group = reflectionGroupRepository.get(groupUuid);
        if (group == null) {
            return null;
        }

        List<Reflection> groupReflections = reflectionsForGroup(groupUuid).stream()
                .sorted(Comparator.comparingLong(Reflection::createdAt).thenComparing(Reflection::uuid))
                .toList();

        ReflectionGroup normalizedGroup = new ReflectionGroup(
                group.uuid(),
            group.toolId(),
                group.name(),
                group.description(),
                group.type(),
            group.baseUrl(),
            group.urlOverrideEnabled(),
            group.bindingSecrets(),
            group.bindingParameters(),
                group.authenticationMode(),
                group.oauthTemplateId(),
                group.groupId(),
                group.artifactId(),
                group.version(),
                group.artifactStatus(),
                group.createdAt(),
                group.updatedAt());

        return new ReflectionGroupExportPackage("1.0", normalizedGroup, groupReflections);
    }

    public ReflectionGroupImportResult importGroup(ReflectionGroupExportPackage pkg) {
        log.debug("ENTER importGroup: [groupUuid={}]",
                pkg != null && pkg.group() != null ? pkg.group().uuid() : "null");

        if (pkg == null || pkg.group() == null || pkg.reflections() == null || pkg.reflections().isEmpty()) {
            return new ReflectionGroupImportResult("error", null, List.of(), "Invalid reflection-group export package.");
        }

        ReflectionGroup incomingGroup = pkg.group();
        ReflectionGroup existingGroup = reflectionGroupRepository.get(incomingGroup.uuid());

        List<Reflection> incomingReflections = pkg.reflections().stream()
                .filter(reflection -> reflection != null && reflection.uuid() != null && !reflection.uuid().isBlank())
                .toList();
        if (incomingReflections.isEmpty()) {
            return new ReflectionGroupImportResult("error", incomingGroup.uuid(), List.of(), "No valid reflections in package.");
        }

        Map<String, String> importTargetUuidByIncomingUuid = new LinkedHashMap<>();
        for (Reflection reflection : incomingReflections) {
            Reflection existingByUuid = reflectionRepository.get(reflection.uuid());

            Reflection conflictById = getReflectionById(reflection.id());
            if (existingByUuid != null && conflictById != null && !existingByUuid.uuid().equals(conflictById.uuid())) {
                return new ReflectionGroupImportResult(
                        "error",
                        incomingGroup.uuid(),
                        List.of(),
                        "Reflection import conflict for id '" + reflection.id() + "': different existing UUIDs would be overwritten.");
            }

            String targetUuid;
            if (existingByUuid != null) {
                targetUuid = existingByUuid.uuid();
            } else if (conflictById != null) {
                targetUuid = conflictById.uuid();
            } else {
                targetUuid = reflection.uuid();
            }
            importTargetUuidByIncomingUuid.put(reflection.uuid(), targetUuid);
        }

        long now = System.currentTimeMillis();
        ReflectionType normalizedType = incomingGroup.type() == null ? ReflectionType.REST : incomingGroup.type();
        ReflectionAuthenticationMode normalizedAuthMode = incomingGroup.authenticationMode() == null
            ? ReflectionAuthenticationMode.NONE
            : incomingGroup.authenticationMode();
        String normalizedOauthTemplateId = normalizeAndValidateOAuthSettings(
            normalizedType,
            normalizedAuthMode,
            incomingGroup.oauthTemplateId());
        String toolId = uniqueGroupToolId(
            incomingGroup.toolId() == null || incomingGroup.toolId().isBlank()
                ? incomingGroup.name()
                : incomingGroup.toolId(),
            incomingGroup.uuid());
        String normalizedGroupId = nonBlank(incomingGroup.groupId(), "legacy");
        String normalizedArtifactId = nonBlank(incomingGroup.artifactId(), "reflectiongroup");
        String normalizedVersion = nonBlank(incomingGroup.version(), "SNAPSHOT");
        String normalizedUuid = toVid(normalizedGroupId, normalizedArtifactId, normalizedVersion);
        ReflectionGroup normalizedGroup = new ReflectionGroup(
                normalizedUuid,
            toolId,
                incomingGroup.name(),
                incomingGroup.description(),
            normalizedType,
            incomingGroup.baseUrl() == null ? "" : incomingGroup.baseUrl(),
            incomingGroup.urlOverrideEnabled(),
            normalizeBindingSecrets(incomingGroup.bindingSecrets()),
            normalizeBindingParameters(incomingGroup.bindingParameters()),
            normalizedAuthMode,
            normalizedOauthTemplateId,
                normalizedGroupId,
                normalizedArtifactId,
                normalizedVersion,
                incomingGroup.artifactStatus() == null ? ArtifactStatus.SNAPSHOT : incomingGroup.artifactStatus(),
                incomingGroup.createdAt() > 0 ? incomingGroup.createdAt() : now,
                incomingGroup.updatedAt() > 0 ? incomingGroup.updatedAt() : now);
        reflectionGroupRepository.save(normalizedGroup);

        List<Reflection> normalizedReflections = incomingReflections.stream()
                .map(reflection -> {
                    String targetUuid = importTargetUuidByIncomingUuid.getOrDefault(reflection.uuid(), reflection.uuid());
                    Reflection existingTarget = reflectionRepository.get(targetUuid);
                    return normalizeImportedReflection(reflection, normalizedGroup.uuid(), targetUuid, existingTarget);
                })
                .toList();
        for (Reflection reflection : normalizedReflections) {
            reflectionRepository.save(reflection);
        }

        List<String> importedUuids = normalizedReflections.stream().map(Reflection::uuid).toList();
        String status = existingGroup == null ? "imported" : "updated";
        return new ReflectionGroupImportResult(status, normalizedGroup.uuid(), importedUuids, null);
    }

    public String executeRestReflection(String reflectionId,
                                        Map<String, Object> runtimeInputs,
                                        String bindingName,
                                        String username) {
        log.debug("ENTER executeRestReflection: [reflectionId={}, bindingName={}, username={}]",
                reflectionId, bindingName, username);
        Reflection reflection = getReflectionById(reflectionId);
        if (reflection == null) {
            return jsonError("Reflection not found: " + reflectionId);
        }

        ReflectionGroup group = reflectionGroupRepository.get(reflection.groupUuid());
        ReflectionType type = group == null ? ReflectionType.REST : group.type();
        if (type == ReflectionType.RECORD) {
            return executeRecordReflection(reflection, runtimeInputs, bindingName, username);
        }
        if (type == ReflectionType.MONGO) {
            return jsonError("MONGO reflections are not executable at this time.");
        }

        ReflectionBinding resolvedBinding;
        Map<String, String> bindingSecretValues;
        Map<String, Object> mergedInputs;
        try {
            resolvedBinding = resolveBindingForExecution(group, bindingName);
            bindingSecretValues = loadBindingSecretValues(username, resolvedBinding, group.bindingSecrets());

            Map<String, Object> cleanedRuntimeInputs = new LinkedHashMap<>(runtimeInputs == null ? Map.of() : runtimeInputs);
            cleanedRuntimeInputs.remove("bindingName");
            mergedInputs = applyBindingInputs(group.bindingParameters(), resolvedBinding, cleanedRuntimeInputs);
            log.debug("Step executeRestReflection.bindingResolved: [groupUuid={}, bindingName={}, inputs={}]",
                    group == null ? null : group.uuid(),
                    resolvedBinding == null ? null : resolvedBinding.name(),
                    sanitizeObjectMapForLog(mergedInputs));
        } catch (Exception ex) {
            return jsonError(ex.getMessage());
        }

        List<String> missing = new ArrayList<>();
        for (ReflectionInputParameter parameter : reflection.inputParameters()) {
            if (!parameter.required()) {
                continue;
            }
            Object value = mergedInputs.get(parameter.name());
            if (value == null || String.valueOf(value).isBlank()) {
                missing.add(parameter.name());
            }
        }
        if (!missing.isEmpty()) {
            return jsonMissing(missing);
        }

        try {
            String method = normalizeMethod(reflection.method());
            String requestContentType = normalizeRequestContentType(reflection.requestContentType());
            Map<String, String> stringInputs = toStringMap(mergedInputs);
            Set<String> optionalTemplateTokens = optionalTemplateTokens(reflection.inputParameters());
            Set<String> optionalArrayTemplateTokens = optionalArrayTemplateTokens(reflection.inputParameters());

            String recordFallbackBaseUrl = null;
            if (type == ReflectionType.RECORD) {
                Object value = ToolExecutionContext.get("__request_base_url__");
                if (value instanceof String s && !s.isBlank()) {
                    recordFallbackBaseUrl = s.trim();
                }
            }

            String rawUrl = applyTemplate(reflection.url(), stringInputs, optionalTemplateTokens);
            rawUrl = stripEmptyQueryParameters(rawUrl);
            List<String> unresolvedUrlTokens = findUnresolvedTemplateTokens(rawUrl);
            if (!unresolvedUrlTokens.isEmpty()) {
                return jsonError("Unresolved URL template parameter(s): "
                        + String.join(", ", unresolvedUrlTokens)
                        + ". Ensure the selected binding has values and placeholder names match the binding parameters.");
            }
            rawUrl = resolveRequestUrl(rawUrl, group, resolvedBinding, recordFallbackBaseUrl);
            rawUrl = substituteSecrets(rawUrl, username, bindingSecretValues);
            URI requestUri = buildUri(rawUrl, reflection.queryParameters(), stringInputs, username, bindingSecretValues, optionalTemplateTokens);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "vork-reflection/1.0");

                Map<String, String> resolvedHeaders = resolveHeaders(
                    reflection.headers(), stringInputs, username, bindingSecretValues, optionalTemplateTokens);

                applyGroupAuthentication(resolvedHeaders, group, resolvedBinding, username);

                if (type == ReflectionType.RECORD) {
                    if (resolvedBinding != null) {
                        putHeaderCaseInsensitive(resolvedHeaders, "X-Vork-Reflection-Binding-UUID", resolvedBinding.uuid());
                        putHeaderCaseInsensitive(resolvedHeaders, "X-Vork-Reflection-Binding-Name", resolvedBinding.name());
                    }
                    String sessionUuid = ToolExecutionContext.getSessionUuid();
                    if (sessionUuid != null && !sessionUuid.isBlank()) {
                        putHeaderCaseInsensitive(resolvedHeaders, "X-Vork-Session-UUID", sessionUuid);
                    }
                    putHeaderCaseInsensitive(resolvedHeaders, "X-Vork-Reflection-Type", "RECORD");
                }

                String body = resolveBody(
                    reflection,
                    method,
                    stringInputs,
                    mergedInputs,
                    username,
                    bindingSecretValues,
                    requestContentType,
                    optionalTemplateTokens,
                    optionalArrayTemplateTokens);

            log.debug("Step executeRestReflection.requestPrepared: [reflectionId={}, method={}, uri={}, headers={}, contentType={}, bodyPreview={}]",
                    reflection.id(),
                    method,
                    requestUri,
                    sanitizeStringMapForLog(resolvedHeaders),
                    requestContentType,
                    sanitizeBodyForLog(body, requestContentType));
            if (body != null) {
                putHeaderCaseInsensitive(resolvedHeaders, "Content-Type", requestContentType);
            }
            resolvedHeaders.forEach(requestBuilder::header);

            HttpRequest.BodyPublisher bodyPublisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);

            requestBuilder.method(method, bodyPublisher);

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            Map<String, Object> responseHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((key, values) -> {
                if (values.size() == 1) {
                    responseHeaders.put(key, values.get(0));
                } else {
                    responseHeaders.put(key, values);
                }
            });

                String responseBody = response.body() == null ? "" : response.body();
                warnIfJsonResponseSchemaMismatch(reflection, responseBody);
                String responseBodyPreview = responseBody.length() > 1000
                    ? responseBody.substring(0, 1000) + "...<truncated>"
                    : responseBody;

            log.debug("EXIT executeRestReflection: [reflectionId={}, statusCode={}, responseHeaders={}, responseBodyPreview={}]",
                    reflection.id(),
                    response.statusCode(),
                    response.headers().map(),
                    responseBodyPreview);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("reflectionId", reflection.id());
            result.put("statusCode", response.statusCode());
            result.put("headers", responseHeaders);
            result.put("body", responseBody);
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            log.warn("Reflection execution failed [id={}]: {}", reflectionId, ex.getMessage());
            return jsonError(ex.getMessage());
        }
    }

    private Reflection normalizeAndValidate(Reflection existing, ReflectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Reflection payload is required.");
        }

        String id = request.id() == null ? "" : request.id().trim();
        if (id.isBlank()) {
            throw new IllegalArgumentException("Reflection id is required.");
        }
        if (!REFLECTION_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Reflection id must be alphanumeric.");
        }

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Reflection name is required.");
        }

        String groupUuid = request.groupUuid() == null ? "" : request.groupUuid().trim();
        if (groupUuid.isBlank()) {
            throw new IllegalArgumentException("groupUuid is required.");
        }

        ReflectionGroup group = reflectionGroupRepository.get(groupUuid);
        if (group == null) {
            throw new IllegalArgumentException("Reflection group not found.");
        }
        if (group.type() != ReflectionType.REST) {
            throw new IllegalArgumentException("Manual reflection creation is only supported for REST groups.");
        }

        if (request.outputSchema() != null && marksMandatoryRecordTool(request.outputSchema())) {
            throw new IllegalArgumentException("Reserved record-tool metadata cannot be set manually.");
        }

        String method = normalizeMethod(request.method());
        String url = request.url() == null ? "" : request.url().trim();
        if (url.isBlank()) {
            throw new IllegalArgumentException("URL is required.");
        }

        validateUniqueId(id, existing == null ? null : existing.uuid());

        List<ReflectionInputParameter> parameters = normalizeInputParameters(request.inputParameters());
        Map<String, String> headers = normalizeStringMap(request.headers());
        Map<String, String> queryParameters = normalizeStringMap(request.queryParameters());
        String requestContentType = normalizeRequestContentType(request.requestContentType());
        String responseContentType = normalizeResponseContentType(request.responseContentType());
        String outputSchema = normalizeOutputSchema(request.outputSchema(), responseContentType);

        long now = System.currentTimeMillis();
        if (existing == null) {
            return new Reflection(
                    UUID.randomUUID().toString(),
                    id,
                    name,
                    request.description() == null ? "" : request.description().trim(),
                    groupUuid,
                    parameters,
                    method,
                    url,
                    headers,
                    queryParameters,
                    request.bodyTemplate() == null ? "" : request.bodyTemplate(),
                    requestContentType,
                    responseContentType,
                    outputSchema,
                    1L,
                    now,
                    now);
        }

        return new Reflection(
                existing.uuid(),
                id,
                name,
                request.description() == null ? "" : request.description().trim(),
                groupUuid,
                parameters,
                method,
                url,
                headers,
                queryParameters,
                request.bodyTemplate() == null ? "" : request.bodyTemplate(),
                requestContentType,
                responseContentType,
                outputSchema,
                existing.version() + 1,
                existing.createdAt(),
                now);
    }

    private void validateUniqueId(String id, String allowedUuid) {
        Reflection duplicate = listReflections().stream()
                .filter(reflection -> id.equalsIgnoreCase(reflection.id()))
                .findFirst()
                .orElse(null);
        if (duplicate == null) {
            return;
        }
        if (allowedUuid != null && allowedUuid.equals(duplicate.uuid())) {
            return;
        }
        throw new IllegalArgumentException("A reflection with id '" + id + "' already exists.");
    }

    private static List<ReflectionInputParameter> normalizeInputParameters(List<ReflectionInputParameter> inputParameters) {
        if (inputParameters == null || inputParameters.isEmpty()) {
            return List.of();
        }
        List<ReflectionInputParameter> normalized = new ArrayList<>();
        for (ReflectionInputParameter parameter : inputParameters) {
            if (parameter == null || parameter.name() == null || parameter.name().isBlank()) {
                continue;
            }
            String type = parameter.type() == null || parameter.type().isBlank()
                    ? "string" : parameter.type().trim().toLowerCase(Locale.ROOT);
            normalized.add(new ReflectionInputParameter(
                    parameter.name().trim(),
                    type,
                    parameter.description() == null ? "" : parameter.description().trim(),
                    parameter.required(),
                    parameter.array()));
        }
        return List.copyOf(normalized);
    }

    private static Map<String, String> normalizeStringMap(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String key = entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    private static ReflectionType parseGroupType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return ReflectionType.REST;
        }
        String normalized = rawType.trim().toUpperCase(Locale.ROOT);
        if ("OAUTH".equals(normalized)) {
            throw new IllegalArgumentException("Reflection group type OAUTH is no longer supported. Use type REST with Authentication mode OAUTH.");
        }
        try {
            return ReflectionType.valueOf(normalized);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unsupported reflection group type: " + rawType);
        }
    }

    private static ReflectionAuthenticationMode parseAuthenticationMode(String rawMode) {
        return ReflectionAuthenticationMode.parse(rawMode);
    }

    private String normalizeAndValidateOAuthSettings(ReflectionType type,
                                                     ReflectionAuthenticationMode authenticationMode,
                                                     String oauthTemplateId) {
        if (authenticationMode == ReflectionAuthenticationMode.NONE) {
            return "";
        }
        if (type != ReflectionType.REST) {
            throw new IllegalArgumentException("OAuth authentication is only supported for REST reflection groups.");
        }
        if (oauthTemplateId == null || oauthTemplateId.isBlank()) {
            throw new IllegalArgumentException("OAuth template is required when authentication mode is OAUTH.");
        }
        String normalized = oauthTemplateId.trim();
        if (oauthTemplateService == null) {
            return normalized;
        }
        UUID templateId;
        try {
            templateId = UUID.fromString(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid OAuth template id.");
        }
        OAuthTemplate template = oauthTemplateService.getTemplate(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Selected OAuth template was not found.");
        }
        return normalized;
    }

    private static String normalizeMethod(String rawMethod) {
        String method = rawMethod == null || rawMethod.isBlank() ? "GET" : rawMethod.trim().toUpperCase(Locale.ROOT);
        return switch (method) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> method;
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };
    }

    private static String normalizeRequestContentType(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return CONTENT_TYPE_JSON;
        }
        String normalized = rawContentType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_CONTENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported request content type: " + rawContentType);
        }
        return normalized;
    }

    private static String normalizeResponseContentType(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return CONTENT_TYPE_JSON;
        }
        String normalized = rawContentType.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_CONTENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported response content type: " + rawContentType);
        }
        return normalized;
    }

    private String normalizeOutputSchema(String rawSchema, String responseContentType) {
        if (rawSchema == null || rawSchema.isBlank()) {
            return "";
        }
        String normalized = rawSchema.trim();
        if (!CONTENT_TYPE_JSON.equals(responseContentType)) {
            return normalized;
        }
        try {
            objectMapper.readTree(normalized);
        } catch (Exception ex) {
            throw new IllegalArgumentException("outputSchema must be valid JSON when responseContentType is application/json.");
        }
        return normalized;
    }

    private Map<String, String> resolveHeaders(Map<String, String> headerTemplates,
                                               Map<String, String> inputValues,
                                               String username,
                                               Map<String, String> bindingSecretValues,
                                               Set<String> optionalTemplateTokens) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (headerTemplates == null || headerTemplates.isEmpty()) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : headerTemplates.entrySet()) {
            String value = applyTemplate(entry.getValue(), inputValues, optionalTemplateTokens);
            value = substituteSecrets(value, username, bindingSecretValues);
            value = oauthClientService.resolveHeaderValue(username, value);
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    private void applyGroupAuthentication(Map<String, String> resolvedHeaders,
                                          ReflectionGroup group,
                                          ReflectionBinding binding,
                                          String username) {
        if (group == null || group.authenticationMode() != ReflectionAuthenticationMode.OAUTH) {
            return;
        }
        OAuthTemplate template = resolveOAuthTemplate(group.oauthTemplateId());
        String clientName = template.clientName();
        String profileName = OAuthClientService.normalizeProfileName(binding.name());
        String token = oauthClientService.resolveAccessToken(username, clientName, profileName);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("OAuth token is unavailable for binding profile '"
                    + profileName
                    + "'. Connect OAuth for the selected template/client first.");
        }
        putHeaderCaseInsensitive(resolvedHeaders, "Authorization", "Bearer " + token);
    }

    private void requireConnectedBindingOAuthProfile(String username, ReflectionGroup group, String bindingName) {
        if (group == null || group.authenticationMode() != ReflectionAuthenticationMode.OAUTH) {
            return;
        }
        OAuthTemplate template = resolveOAuthTemplate(group.oauthTemplateId());
        String profileName = OAuthClientService.normalizeProfileName(bindingName);
        String token = oauthClientService.resolveAccessToken(username, template.clientName(), profileName);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("OAuth is required before creating binding '"
                    + profileName
                    + "'. Connect the template first via /api/oauth-templates/"
                    + template.id()
                    + "/connect for client '"
                    + template.clientName()
                    + "'.");
        }
    }

    private void clearBindingOAuthProfile(String username, ReflectionGroup group, String bindingName) {
        if (group == null || group.authenticationMode() != ReflectionAuthenticationMode.OAUTH) {
            return;
        }
        OAuthTemplate template = resolveOAuthTemplate(group.oauthTemplateId());
        oauthClientService.deleteProfile(username, template.clientName(), bindingName);
    }

    private OAuthTemplate resolveOAuthTemplate(String oauthTemplateId) {
        if (oauthTemplateService == null) {
            throw new IllegalArgumentException("OAuth template service is unavailable.");
        }
        if (oauthTemplateId == null || oauthTemplateId.isBlank()) {
            throw new IllegalArgumentException("OAuth template is required for OAUTH authentication mode.");
        }
        try {
            UUID templateId = UUID.fromString(oauthTemplateId.trim());
            OAuthTemplate template = oauthTemplateService.getTemplate(templateId);
            if (template == null) {
                throw new IllegalArgumentException("Selected OAuth template was not found.");
            }
            return template;
        } catch (IllegalArgumentException ex) {
            if ("Selected OAuth template was not found.".equals(ex.getMessage())) {
                throw ex;
            }
            throw new IllegalArgumentException("Invalid OAuth template id.");
        }
    }

    private URI buildUri(String rawUrl,
                         Map<String, String> baseQueryParams,
                         Map<String, String> inputValues,
                         String username,
                         Map<String, String> bindingSecretValues,
                         Set<String> optionalTemplateTokens) {
        StringBuilder url = new StringBuilder(rawUrl == null ? "" : rawUrl.trim());
        boolean hasQuery = url.indexOf("?") >= 0;

        Map<String, String> merged = new LinkedHashMap<>();
        if (baseQueryParams != null) {
            for (Map.Entry<String, String> entry : baseQueryParams.entrySet()) {
                String resolvedValue = applyTemplate(entry.getValue(), inputValues, optionalTemplateTokens);
                resolvedValue = substituteSecrets(resolvedValue, username, bindingSecretValues);
                if (resolvedValue == null || resolvedValue.isBlank()) {
                    continue;
                }
                merged.put(entry.getKey(), resolvedValue);
            }
        }

        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!hasQuery) {
                url.append('?');
                hasQuery = true;
            } else if (url.charAt(url.length() - 1) != '?' && url.charAt(url.length() - 1) != '&') {
                url.append('&');
            }
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return toUriWithSpaceEncoding(stripEmptyQueryParameters(url.toString()));
    }

    private String resolveBody(Reflection reflection,
                               String method,
                               Map<String, String> stringInputs,
                               Map<String, Object> rawInputs,
                               String username,
                               Map<String, String> bindingSecretValues,
                               String requestContentType,
                               Set<String> optionalTemplateTokens,
                               Set<String> optionalArrayTemplateTokens) throws Exception {
        if (METHODS_WITHOUT_BODY.contains(method)) {
            return null;
        }

        String bodyTemplate = reflection.bodyTemplate();
        if (bodyTemplate != null && !bodyTemplate.isBlank()) {
            String body = applyBodyTemplate(bodyTemplate,
                    stringInputs,
                    requestContentType,
                    optionalTemplateTokens,
                    optionalArrayTemplateTokens);
            if (CONTENT_TYPE_JSON.equals(requestContentType)) {
                body = pruneEmptyOptionalJsonArtifacts(body);
            }
            return substituteSecrets(body, username, bindingSecretValues);
        }

        if (rawInputs == null || rawInputs.isEmpty()) {
            return null;
        }

        Map<String, Object> generated = buildGeneratedBodyMap(reflection.inputParameters(), rawInputs);
        if (generated.isEmpty()) {
            return null;
        }

        return switch (requestContentType) {
            case CONTENT_TYPE_FORM -> toFormEncoded(generated);
            case CONTENT_TYPE_TEXT -> toPlainText(generated);
            default -> objectMapper.writeValueAsString(generated);
        };
    }

    private static void putHeaderCaseInsensitive(Map<String, String> headers, String key, String value) {
        String existing = headers.keySet().stream()
                .filter(k -> key.equalsIgnoreCase(k))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            headers.remove(existing);
        }
        headers.put(key, value);
    }

    private Map<String, Object> buildGeneratedBodyMap(List<ReflectionInputParameter> inputParameters,
                                                      Map<String, Object> rawInputs) {
        Map<String, Object> generated = new LinkedHashMap<>();
        Set<String> consumed = new LinkedHashSet<>();

        if (inputParameters != null) {
            for (ReflectionInputParameter parameter : inputParameters) {
                if (parameter == null || parameter.name() == null || parameter.name().isBlank()) {
                    continue;
                }
                String name = parameter.name().trim();
                consumed.add(name);
                if (!rawInputs.containsKey(name)) {
                    continue;
                }
                generated.put(name, coerceByType(rawInputs.get(name), parameter.type(), parameter.array()));
            }
        }

        for (Map.Entry<String, Object> entry : rawInputs.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || consumed.contains(entry.getKey())) {
                continue;
            }
            generated.put(entry.getKey(), entry.getValue());
        }

        return generated;
    }

    private static Object coerceByType(Object value, String type) {
        return coerceByType(value, type, false);
    }

    private static Object coerceByType(Object value, String type, boolean isArray) {
        if (value == null) {
            return null;
        }
        if (isArray) {
            return coerceToArray(value, type);
        }

        return coerceScalar(value, type);
    }

    private static Object coerceScalar(Object value, String type) {
        if (value == null) {
            return null;
        }
        String normalized = type == null ? "string" : type.trim().toLowerCase(Locale.ROOT);
        String raw = String.valueOf(value);
        try {
            return switch (normalized) {
                case "int", "integer" -> Integer.parseInt(raw);
                case "double", "number", "float" -> Double.parseDouble(raw);
                case "boolean", "bool" -> Boolean.parseBoolean(raw);
                default -> raw;
            };
        } catch (Exception ex) {
            return raw;
        }
    }

    private static List<Object> coerceToArray(Object value, String type) {
        if (value == null) {
            return List.of();
        }

        if (value instanceof List<?> list) {
            List<Object> coerced = new ArrayList<>();
            for (Object item : list) {
                coerced.add(coerceScalar(item, type));
            }
            return List.copyOf(coerced);
        }

        if (value instanceof Object[] array) {
            List<Object> coerced = new ArrayList<>();
            for (Object item : array) {
                coerced.add(coerceScalar(item, type));
            }
            return List.copyOf(coerced);
        }

        if (value instanceof String rawString) {
            String trimmed = rawString.trim();
            if (trimmed.isBlank()) {
                return List.of();
            }
            String[] parts = trimmed.split(",");
            List<Object> coerced = new ArrayList<>();
            for (String part : parts) {
                String token = part == null ? "" : part.trim();
                if (token.isBlank()) {
                    continue;
                }
                coerced.add(coerceScalar(token, type));
            }
            return List.copyOf(coerced);
        }

        return List.of(coerceScalar(value, type));
    }

    private static String applyBodyTemplate(String template,
                                            Map<String, String> params,
                                            String requestContentType,
                                            Set<String> optionalTemplateTokens,
                                            Set<String> optionalArrayTemplateTokens) {
        if (template == null || template.isBlank()) {
            return template == null ? "" : template;
        }

        Map<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (params != null && !params.isEmpty()) {
            caseInsensitive.putAll(params);
        }

        String workingTemplate = template;
        Set<String> missingOptionalTokens = new LinkedHashSet<>();
        if (optionalTemplateTokens != null && !optionalTemplateTokens.isEmpty()) {
            for (String tokenName : optionalTemplateTokens) {
                if (tokenName == null || tokenName.isBlank()) {
                    continue;
                }
                if (!containsTokenIgnoreCase(caseInsensitive.keySet(), tokenName)) {
                    missingOptionalTokens.add(tokenName);
                }
            }
        }

        if (CONTENT_TYPE_JSON.equals(requestContentType)
                && !missingOptionalTokens.isEmpty()) {
            for (String tokenName : missingOptionalTokens) {
                if (tokenName == null || tokenName.isBlank()) {
                    continue;
                }
                Pattern quotedTokenPattern = Pattern.compile("\"\\s*\\{\\{\\s*"
                        + Pattern.quote(tokenName)
                        + "\\s*\\}\\}\\s*\"", Pattern.CASE_INSENSITIVE);
                workingTemplate = quotedTokenPattern.matcher(workingTemplate)
                        .replaceAll("{{" + tokenName + "}}");
            }
        }

        Matcher matcher = TEMPLATE_TOKEN_PATTERN.matcher(workingTemplate);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!caseInsensitive.containsKey(token) && !containsTokenIgnoreCase(optionalTemplateTokens, token)) {
                continue;
            }
            boolean missingOptionalArray = !caseInsensitive.containsKey(token)
                    && containsTokenIgnoreCase(optionalArrayTemplateTokens, token)
                    && CONTENT_TYPE_JSON.equals(requestContentType);
            boolean missingOptionalScalar = !caseInsensitive.containsKey(token)
                    && containsTokenIgnoreCase(optionalTemplateTokens, token)
                    && CONTENT_TYPE_JSON.equals(requestContentType)
                    && !containsTokenIgnoreCase(optionalArrayTemplateTokens, token);

            String encoded;
            if (missingOptionalArray) {
                encoded = "[]";
            } else if (missingOptionalScalar) {
                encoded = "null";
            } else {
                String rawValue = caseInsensitive.containsKey(token) ? caseInsensitive.get(token) : "";
                encoded = encodeTemplateValue(rawValue == null ? "" : rawValue, requestContentType);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(encoded));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String encodeTemplateValue(String value, String requestContentType) {
        return switch (requestContentType) {
            case CONTENT_TYPE_FORM -> URLEncoder.encode(value, StandardCharsets.UTF_8);
            case CONTENT_TYPE_JSON -> escapeJsonString(value);
            default -> value;
        };
    }

    private static String escapeJsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String toFormEncoded(Map<String, Object> values) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(entry.getValue() == null ? "" : String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private static String toPlainText(Map<String, Object> values) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(entry.getKey()).append('=')
                    .append(entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return body.toString();
    }

    private String substituteSecrets(String value, String username, Map<String, String> bindingSecretValues) {
        if (value == null) {
            return null;
        }
        String resolved = value;
        if (bindingSecretValues != null && !bindingSecretValues.isEmpty()) {
            for (Map.Entry<String, String> entry : bindingSecretValues.entrySet()) {
                resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return skillSecretSubstitutor.substitute(resolved, username);
    }

    private ReflectionGroup requireGroup(String groupUuid) {
        if (groupUuid == null || groupUuid.isBlank()) {
            throw new IllegalArgumentException("groupUuid is required.");
        }
        ReflectionGroup group = reflectionGroupRepository.get(groupUuid);
        if (group == null) {
            throw new IllegalArgumentException("Reflection group not found.");
        }
        return group;
    }

    private static void requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authenticated username is required.");
        }
    }

    private static List<SkillSecret> normalizeBindingSecrets(List<SkillSecret> bindingSecrets) {
        if (bindingSecrets == null || bindingSecrets.isEmpty()) {
            return List.of();
        }
        List<SkillSecret> normalized = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (SkillSecret secret : bindingSecrets) {
            if (secret == null || secret.name() == null || secret.name().isBlank()) {
                continue;
            }
            String name = secret.name().trim();
            if (!names.add(name.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate binding secret name: " + name);
            }
            normalized.add(new SkillSecret(name, secret.description()));
        }
        return List.copyOf(normalized);
    }

    private static List<ReflectionBindingParameter> normalizeBindingParameters(
            List<ReflectionBindingParameter> bindingParameters) {
        if (bindingParameters == null || bindingParameters.isEmpty()) {
            return List.of();
        }
        List<ReflectionBindingParameter> normalized = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (ReflectionBindingParameter parameter : bindingParameters) {
            if (parameter == null || parameter.name() == null || parameter.name().isBlank()) {
                continue;
            }
            String name = parameter.name().trim();
            String type = parameter.type() == null ? "string" : parameter.type().trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_BINDING_PARAMETER_TYPES.contains(type)) {
                throw new IllegalArgumentException("Unsupported binding parameter type: " + parameter.type());
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate binding parameter name: " + name);
            }
            normalized.add(new ReflectionBindingParameter(name, type, parameter.description(), parameter.defaultValue()));
        }
        return List.copyOf(normalized);
    }

    private ReflectionBinding normalizeBindingRequest(ReflectionBinding existing,
                                                     ReflectionGroup group,
                                                     ReflectionBindingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Binding payload is required.");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("Binding name is required.");
        }
        if (!BINDING_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Binding name can use letters, numbers, dot, underscore, and hyphen only.");
        }

        Map<String, String> parameterValues = normalizeAndValidateBindingValues(group.bindingParameters(), request.parameterValues());
        String reflectionUuid = resolveBindingReflectionUuid(existing, group, request);
        long now = System.currentTimeMillis();
        if (existing == null) {
            return new ReflectionBinding(
                    UUID.randomUUID().toString(),
                reflectionUuid,
                    name,
                    isUrlOverrideEnabled(group) && request.baseUrl() != null ? request.baseUrl().trim() : "",
                    parameterValues,
                    1L,
                    now,
                    now);
        }

        return new ReflectionBinding(
                existing.uuid(),
                reflectionUuid,
                name,
                isUrlOverrideEnabled(group) && request.baseUrl() != null ? request.baseUrl().trim() : "",
                parameterValues,
                existing.version() + 1,
                existing.createdAt(),
                now);
    }

    private String resolveBindingReflectionUuid(ReflectionBinding existing,
                                                ReflectionGroup group,
                                                ReflectionBindingRequest request) {
        String requestedReflectionUuid = request.reflectionUuid() == null ? "" : request.reflectionUuid().trim();
        if (!requestedReflectionUuid.isBlank()) {
            Reflection requested = getReflection(requestedReflectionUuid);
            if (requested == null) {
                if (group.uuid().equals(requestedReflectionUuid)) {
                    return group.uuid();
                }
                throw new IllegalArgumentException("Reflection not found for reflectionUuid.");
            }
            if (!group.uuid().equals(requested.groupUuid())) {
                throw new IllegalArgumentException("reflectionUuid must belong to the selected group.");
            }
            return requested.uuid();
        }

        if (existing != null && existing.reflectionUuid() != null && !existing.reflectionUuid().isBlank()) {
            Reflection prior = getReflection(existing.reflectionUuid());
            if (prior != null && group.uuid().equals(prior.groupUuid())) {
                return existing.reflectionUuid();
            }
            if (group.uuid().equals(existing.reflectionUuid())) {
                return group.uuid();
            }
        }

        List<Reflection> groupReflections = reflectionsForGroup(group.uuid());
        if (groupReflections.isEmpty()) {
            return group.uuid();
        }
        return groupReflections.getFirst().uuid();
    }

    private static boolean isUrlOverrideEnabled(ReflectionGroup group) {
        return group == null || !Boolean.FALSE.equals(group.urlOverrideEnabled());
    }

    private static Map<String, String> normalizeAndValidateBindingValues(
            List<ReflectionBindingParameter> definitions,
            Map<String, String> values) {
        Map<String, String> normalized = normalizeStringMap(values);
        if (definitions == null || definitions.isEmpty()) {
            return normalized;
        }

        Set<String> allowedNames = new LinkedHashSet<>();
        for (ReflectionBindingParameter definition : definitions) {
            allowedNames.add(definition.name().toLowerCase(Locale.ROOT));
        }
        for (String key : normalized.keySet()) {
            if (!allowedNames.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unknown binding parameter: " + key);
            }
        }
        return normalized;
    }

    private void enforceDefaultBindingInvariant(String groupUuid, String existingName, String targetName) {
        Set<String> names = new LinkedHashSet<>();
        for (ReflectionBinding binding : bindingsForGroup(groupUuid)) {
            String current = binding.name();
            if (existingName != null && existingName.equalsIgnoreCase(current)) {
                continue;
            }
            names.add(current.toLowerCase(Locale.ROOT));
        }
        names.add(targetName.toLowerCase(Locale.ROOT));
        if (!names.contains("default")) {
            throw new IllegalArgumentException("A default binding is required for this group.");
        }
    }

    private void saveBindingSecrets(String username,
                                    ReflectionBinding binding,
                                    List<SkillSecret> secretDefinitions,
                                    Map<String, String> secretValues) {
        if (secretDefinitions == null || secretDefinitions.isEmpty() || secretValues == null || secretValues.isEmpty()) {
            return;
        }

        Map<String, String> normalizedValues = normalizeStringMap(secretValues);
        Set<String> allowed = new LinkedHashSet<>();
        for (SkillSecret secret : secretDefinitions) {
            allowed.add(secret.name().toUpperCase(Locale.ROOT));
        }

        for (Map.Entry<String, String> entry : normalizedValues.entrySet()) {
            String key = entry.getKey().toUpperCase(Locale.ROOT);
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown binding secret: " + entry.getKey());
            }
            secureCredentialStore.saveGlobalSecret(bindingSecretStorageKey(binding, key), entry.getValue());
        }
    }

    private Map<String, String> mergeSecretValuesFromSourceBinding(String username,
                                                                    String groupUuid,
                                                                    ReflectionBindingRequest request) {
        Map<String, String> merged = new LinkedHashMap<>(normalizeStringMap(request.secretValues()));

        String sourceBindingName = request.copySecretsFromBindingName();
        if (sourceBindingName == null || sourceBindingName.isBlank()) {
            return Map.copyOf(merged);
        }

        ReflectionBinding source = getBinding(groupUuid, sourceBindingName);
        if (source == null) {
            return Map.copyOf(merged);
        }

        ReflectionGroup group = requireGroup(groupUuid);
        for (SkillSecret secret : group.bindingSecrets()) {
            String secretName = secret.name();
            boolean alreadyProvided = merged.keySet().stream()
                    .anyMatch(key -> key.equalsIgnoreCase(secretName));
            if (alreadyProvided) {
                continue;
            }

            String sourceValue = secureCredentialStore.getGlobalSecret(bindingSecretStorageKey(source, secretName));
            if (sourceValue == null || sourceValue.isBlank()) {
                // Backward compatibility for existing per-user stored binding secrets.
                sourceValue = secureCredentialStore.getSecretForUser(
                        username,
                        bindingSecretStorageKey(source, secretName));
            }
            if (sourceValue != null && !sourceValue.isBlank()) {
                merged.put(secretName, sourceValue);
            }
        }
        return Map.copyOf(merged);
    }

    private void clearBindingSecrets(String username, ReflectionBinding binding) {
        // Keep historical secret records untouched; deleting is optional and not required for runtime safety.
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(binding, "binding");
    }

    private static String bindingSecretStorageKey(ReflectionBinding binding, String secretName) {
        String normalizedSecretName = secretName == null ? "" : secretName.toUpperCase(Locale.ROOT);
        return "REFLECTION_BINDING:" + binding.reflectionUuid() + ":" + binding.name() + ":" + normalizedSecretName;
    }

    private ReflectionBinding resolveBindingForExecution(ReflectionGroup group, String bindingName) {
        List<ReflectionBinding> bindings = bindingsForGroup(group.uuid());
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("No bindings configured for group: " + group.name());
        }

        if (bindingName != null && !bindingName.isBlank()) {
            ReflectionBinding named = bindings.stream()
                    .filter(binding -> bindingName.equalsIgnoreCase(binding.name()))
                    .findFirst()
                    .orElse(null);
            if (named == null) {
                throw new IllegalArgumentException("Binding not found: " + bindingName);
            }
            return named;
        }

        return bindings.stream()
                .filter(binding -> "default".equalsIgnoreCase(binding.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Default binding not found for group: " + group.name()));
    }

    private Map<String, String> loadBindingSecretValues(String username,
                                                        ReflectionBinding binding,
                                                        List<SkillSecret> secretDefinitions) {
        if (username == null || username.isBlank() || binding == null || secretDefinitions == null || secretDefinitions.isEmpty()) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (SkillSecret secret : secretDefinitions) {
            String name = secret.name();
            String value = secureCredentialStore.getGlobalSecret(bindingSecretStorageKey(binding, name));
            if (value == null) {
                // Backward compatibility for existing per-user stored binding secrets.
                value = secureCredentialStore.getSecretForUser(username, bindingSecretStorageKey(binding, name));
            }
            if (value != null) {
                values.put(name, value);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, Object> applyBindingInputs(List<ReflectionBindingParameter> parameters,
                                                          ReflectionBinding binding,
                                                          Map<String, Object> runtimeInputs) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (parameters != null) {
            for (ReflectionBindingParameter parameter : parameters) {
                String name = parameter.name();
                String defaultValue = parameter.defaultValue();
                if (defaultValue != null && !defaultValue.isBlank()) {
                    merged.put(name, coerceByType(defaultValue, parameter.type()));
                }
            }
        }

        if (binding != null && binding.parameterValues() != null) {
            for (Map.Entry<String, String> entry : binding.parameterValues().entrySet()) {
                String type = "string";
                if (parameters != null) {
                    for (ReflectionBindingParameter parameter : parameters) {
                        if (parameter.name().equalsIgnoreCase(entry.getKey())) {
                            type = parameter.type();
                            break;
                        }
                    }
                }
                merged.put(entry.getKey(), coerceByType(entry.getValue(), type));
            }
        }

        if (runtimeInputs != null) {
            merged.putAll(runtimeInputs);
        }
        return merged;
    }

    private static String resolveRequestUrl(String reflectionUrl,
                                            ReflectionGroup group,
                                            ReflectionBinding binding,
                                            String fallbackBaseUrl) {
        String url = reflectionUrl == null ? "" : reflectionUrl.trim();
        if (url.isBlank()) {
            return url;
        }
        if (isAbsoluteUrl(url)) {
            return url;
        }

        String normalizedHostUrl = normalizeHostOnlyUrl(url);
        if (normalizedHostUrl != null) {
            return normalizedHostUrl;
        }

        String baseUrl = "";
        if (binding != null && binding.baseUrl() != null && !binding.baseUrl().isBlank()) {
            baseUrl = binding.baseUrl().trim();
        } else if (group.baseUrl() != null && !group.baseUrl().isBlank()) {
            baseUrl = group.baseUrl().trim();
        }
        if (baseUrl.isBlank() && fallbackBaseUrl != null && !fallbackBaseUrl.isBlank()) {
            baseUrl = fallbackBaseUrl.trim();
        }
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("Relative URL requires a group or binding base URL.");
        }

        if (baseUrl.endsWith("/") && url.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + url;
        }
        if (!baseUrl.endsWith("/") && !url.startsWith("/")) {
            return baseUrl + "/" + url;
        }
        return baseUrl + url;
    }

    private static boolean isAbsoluteUrl(String url) {
        if (url == null) {
            return false;
        }
        String candidate = url.trim();
        if (candidate.isBlank()) {
            return false;
        }
        // Scheme check must be tolerant of unresolved/unsafe query chars before final URI encoding.
        return candidate.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");
    }

    private static URI toUriWithSpaceEncoding(String rawUrl) {
        try {
            return URI.create(rawUrl);
        } catch (IllegalArgumentException ex) {
            if (rawUrl != null && rawUrl.contains(" ")) {
                return URI.create(rawUrl.replace(" ", "%20"));
            }
            throw ex;
        }
    }

    /**
     * Accept host-style values (e.g. api.example.com/path) by defaulting to HTTPS.
     * This supports binding-driven host parameters used directly in URL templates.
     */
    private static String normalizeHostOnlyUrl(String url) {
        if (url == null) {
            return null;
        }
        String candidate = url.trim();
        if (candidate.isBlank()) {
            return null;
        }
        if (candidate.startsWith("//")) {
            return "https:" + candidate;
        }

        int slash = candidate.indexOf('/');
        String hostPart = slash >= 0 ? candidate.substring(0, slash) : candidate;
        if (hostPart.isBlank() || hostPart.contains(" ")) {
            return null;
        }

        boolean looksLikeHost = hostPart.contains(".")
                || hostPart.startsWith("localhost")
                || hostPart.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?");
        if (!looksLikeHost) {
            return null;
        }

        return "https://" + candidate;
    }

    private static String applyTemplate(String template,
                                        Map<String, String> params,
                                        Set<String> missingAsEmptyTokens) {
        if (template == null || template.isBlank()) {
            return template == null ? "" : template;
        }
        Map<String, String> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (params != null && !params.isEmpty()) {
            caseInsensitive.putAll(params);
        }

        Matcher matcher = TEMPLATE_TOKEN_PATTERN.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!caseInsensitive.containsKey(token) && !containsTokenIgnoreCase(missingAsEmptyTokens, token)) {
                continue;
            }
            String value = caseInsensitive.containsKey(token) ? caseInsensitive.get(token) : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Set<String> optionalTemplateTokens(List<ReflectionInputParameter> inputParameters) {
        if (inputParameters == null || inputParameters.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (ReflectionInputParameter parameter : inputParameters) {
            if (parameter == null || parameter.name() == null || parameter.name().isBlank() || parameter.required()) {
                continue;
            }
            tokens.add(parameter.name().trim().toLowerCase(Locale.ROOT));
        }
        return tokens.isEmpty() ? Set.of() : Set.copyOf(tokens);
    }

    private static Set<String> optionalArrayTemplateTokens(List<ReflectionInputParameter> inputParameters) {
        if (inputParameters == null || inputParameters.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (ReflectionInputParameter parameter : inputParameters) {
            if (parameter == null
                    || parameter.name() == null
                    || parameter.name().isBlank()
                    || parameter.required()
                    || !parameter.array()) {
                continue;
            }
            tokens.add(parameter.name().trim().toLowerCase(Locale.ROOT));
        }
        return tokens.isEmpty() ? Set.of() : Set.copyOf(tokens);
    }

    private String pruneEmptyOptionalJsonArtifacts(String body) {
        if (body == null || body.isBlank()) {
            return body == null ? "" : body;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode pruned = pruneJsonNode(root);
            return objectMapper.writeValueAsString(pruned);
        } catch (Exception ex) {
            return body;
        }
    }

    private JsonNode pruneJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return NullNode.instance;
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node.deepCopy();
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode child = pruneJsonNode(objectNode.get(fieldName));
                if (child == null || child.isNull() || (child.isObject() && child.size() == 0)) {
                    objectNode.remove(fieldName);
                } else {
                    objectNode.set(fieldName, child);
                }
            }
            return objectNode;
        }

        if (node.isArray()) {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                JsonNode child = pruneJsonNode(item);
                if (child == null || child.isNull() || (child.isObject() && child.size() == 0)) {
                    continue;
                }
                arrayNode.add(child);
            }
            return arrayNode;
        }

        return node;
    }

    private static boolean containsTokenIgnoreCase(Set<String> tokens, String token) {
        if (token == null || token.isBlank() || tokens == null || tokens.isEmpty()) {
            return false;
        }
        return tokens.contains(token.toLowerCase(Locale.ROOT));
    }

    private static String stripEmptyQueryParameters(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl == null ? "" : rawUrl;
        }

        int queryStart = rawUrl.indexOf('?');
        if (queryStart < 0 || queryStart == rawUrl.length() - 1) {
            return rawUrl;
        }

        String base = rawUrl.substring(0, queryStart);
        String queryAndFragment = rawUrl.substring(queryStart + 1);
        String fragment = "";
        int fragmentStart = queryAndFragment.indexOf('#');
        if (fragmentStart >= 0) {
            fragment = queryAndFragment.substring(fragmentStart);
            queryAndFragment = queryAndFragment.substring(0, fragmentStart);
        }

        List<String> kept = new ArrayList<>();
        for (String segment : queryAndFragment.split("&")) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            int equalsIndex = segment.indexOf('=');
            if (equalsIndex < 0) {
                kept.add(segment);
                continue;
            }
            String value = segment.substring(equalsIndex + 1);
            if (value.isBlank()) {
                continue;
            }
            kept.add(segment);
        }

        if (kept.isEmpty()) {
            return base + fragment;
        }
        return base + "?" + String.join("&", kept) + fragment;
    }

    private static List<String> findUnresolvedTemplateTokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Matcher matcher = TEMPLATE_TOKEN_PATTERN.matcher(value);
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        while (matcher.find()) {
            unresolved.add(matcher.group(1));
        }
        return unresolved.isEmpty() ? List.of() : List.copyOf(unresolved);
    }

        private Reflection normalizeImportedReflection(Reflection reflection,
                               String groupUuid,
                               String targetUuid,
                               Reflection existingTarget) {
        long now = System.currentTimeMillis();
        long createdAt = existingTarget != null && existingTarget.createdAt() > 0
            ? existingTarget.createdAt()
            : (reflection.createdAt() > 0 ? reflection.createdAt() : now);
        long updatedAt = now;
        long version = existingTarget != null
            ? Math.max(existingTarget.version() + 1, 1)
            : (reflection.version() < 1 ? 1 : reflection.version());

        String responseContentType = normalizeResponseContentType(reflection.responseContentType());
        String outputSchema = normalizeOutputSchema(reflection.outputSchema(), responseContentType);

        return new Reflection(
            targetUuid,
                reflection.id(),
                reflection.name(),
                reflection.description(),
                groupUuid,
                normalizeInputParameters(reflection.inputParameters()),
                normalizeMethod(reflection.method()),
                reflection.url() == null ? "" : reflection.url(),
                normalizeStringMap(reflection.headers()),
                normalizeStringMap(reflection.queryParameters()),
                reflection.bodyTemplate() == null ? "" : reflection.bodyTemplate(),
                normalizeRequestContentType(reflection.requestContentType()),
                responseContentType,
                outputSchema,
                version,
                createdAt,
                updatedAt);
    }

    private void warnIfJsonResponseSchemaMismatch(Reflection reflection, String responseBody) {
        if (reflection == null || responseBody == null) {
            return;
        }
        if (!CONTENT_TYPE_JSON.equals(normalizeResponseContentType(reflection.responseContentType()))) {
            return;
        }
        if (reflection.outputSchema() == null || reflection.outputSchema().isBlank()) {
            return;
        }

        try {
            JsonNode schema = objectMapper.readTree(reflection.outputSchema());
            JsonNode payload = objectMapper.readTree(responseBody);
            List<String> issues = new ArrayList<>();
            validateJsonAgainstSchema(payload, schema, "$", issues);
            if (!issues.isEmpty()) {
                log.warn("Reflection response schema mismatch [id={}]: {}",
                        reflection.id(),
                        issues.size() == 1
                                ? issues.get(0)
                                : issues.get(0) + " (and " + (issues.size() - 1) + " more issue(s))");
            }
        } catch (Exception ex) {
            log.warn("Reflection response schema validation failed [id={}]: {}",
                    reflection.id(), ex.getMessage());
        }
    }

    private static void validateJsonAgainstSchema(JsonNode value, JsonNode schema, String path, List<String> issues) {
        if (schema == null || issues == null) {
            return;
        }
        if (schema.isBoolean()) {
            if (!schema.booleanValue()) {
                issues.add(path + " is disallowed by boolean schema false.");
            }
            return;
        }
        if (!schema.isObject()) {
            return;
        }

        JsonNode typeNode = schema.get("type");
        if (typeNode != null && !matchesDeclaredType(value, typeNode)) {
            issues.add(path + " does not match schema type " + typeNode.toString() + ".");
            return;
        }

        if (value != null && value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode field : required) {
                    if (field.isTextual() && !value.has(field.textValue())) {
                        issues.add(path + "." + field.textValue() + " is required.");
                    }
                }
            }

            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                properties.properties().iterator().forEachRemaining(entry -> {
                    JsonNode child = value.get(entry.getKey());
                    if (child != null) {
                        validateJsonAgainstSchema(child, entry.getValue(), path + "." + entry.getKey(), issues);
                    }
                });
            }

            JsonNode additionalProperties = schema.get("additionalProperties");
            if (additionalProperties != null && additionalProperties.isBoolean() && !additionalProperties.booleanValue()) {
                JsonNode propertiesNode = schema.get("properties");
                value.fieldNames().forEachRemaining(name -> {
                    if (propertiesNode == null || !propertiesNode.has(name)) {
                        issues.add(path + "." + name + " is not allowed by additionalProperties=false.");
                    }
                });
            }
        }

        if (value != null && value.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int i = 0; i < value.size(); i++) {
                    validateJsonAgainstSchema(value.get(i), items, path + "[" + i + "]", issues);
                }
            }
        }
    }

    private static boolean matchesDeclaredType(JsonNode value, JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return matchesSingleType(value, typeNode.textValue());
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                if (candidate.isTextual() && matchesSingleType(value, candidate.textValue())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesSingleType(JsonNode value, String schemaType) {
        if (schemaType == null) {
            return true;
        }
        return switch (schemaType) {
            case "object" -> value != null && value.isObject();
            case "array" -> value != null && value.isArray();
            case "string" -> value != null && value.isTextual();
            case "integer" -> value != null && value.isIntegralNumber();
            case "number" -> value != null && value.isNumber();
            case "boolean" -> value != null && value.isBoolean();
            case "null" -> value == null || value.isNull();
            default -> true;
        };
    }

    private static Map<String, String> toStringMap(Map<String, Object> rawInputs) {
        if (rawInputs == null || rawInputs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawInputs.entrySet()) {
            out.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }

    private static Map<String, Object> sanitizeObjectMapForLog(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        if (DEV_UNREDACTED_LOGS) {
            return Map.copyOf(input);
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (isSensitiveKey(key)) {
                sanitized.put(key, "[REDACTED]");
            } else {
                sanitized.put(key, entry.getValue());
            }
        }
        return Map.copyOf(sanitized);
    }

    private static Map<String, String> sanitizeStringMapForLog(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        if (DEV_UNREDACTED_LOGS) {
            return Map.copyOf(input);
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (isSensitiveKey(key)) {
                sanitized.put(key, "[REDACTED]");
            } else {
                sanitized.put(key, entry.getValue());
            }
        }
        return Map.copyOf(sanitized);
    }

    private static String sanitizeBodyForLog(String body, String contentType) {
        if (body == null) {
            return "<none>";
        }

        if (DEV_UNREDACTED_LOGS) {
            return body.length() > 1000 ? body.substring(0, 1000) + "...<truncated>" : body;
        }

        String sanitized = body;
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (CONTENT_TYPE_FORM.equals(normalizedType)) {
            StringBuilder out = new StringBuilder();
            String[] pairs = body.split("&");
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    out.append('&');
                }
                String pair = pairs[i];
                int separator = pair.indexOf('=');
                String key = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                if (isSensitiveKey(key)) {
                    out.append(key).append("=[REDACTED]");
                } else {
                    out.append(key).append('=').append(value);
                }
            }
            sanitized = out.toString();
        } else {
            sanitized = sanitized
                    .replaceAll("(?i)(\\\"(?:password|secret|token|api[_-]?key)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")", "$1[REDACTED]$3")
                    .replaceAll("(?i)(password|secret|token|api[_-]?key)=([^&\\s]+)", "$1=[REDACTED]");
        }

        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) + "...<truncated>" : sanitized;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("api_key")
                || normalized.contains("apikey");
    }

    private static boolean resolveDevUnredactedLogsFlag() {
        String systemProperty = System.getProperty("vork.dev.unredacted.logs");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return Boolean.parseBoolean(systemProperty.trim());
        }
        String env = System.getenv("VORK_DEV_UNREDACTED_LOGS");
        return env != null && Boolean.parseBoolean(env.trim());
    }

    private String uniqueGroupToolId(String preferredSource, String excludeGroupUuid) {
        return ToolIdGenerator.unique(
                preferredSource,
                "group",
                candidate -> isGroupToolIdAvailable(candidate, excludeGroupUuid));
    }

    private void upsertMandatoryRecordToolReflection(ReflectionGroup group,
                                                     String recordFqn,
                                                     String simpleName,
                                                     String operation,
                                                     String name,
                                                     String description,
                                                     List<ReflectionInputParameter> inputParameters) {
        RecordReflection existing = getRecordReflectionByMetadata(recordFqn, operation);
        Reflection legacy = getHttpRecordReflectionByMetadata(recordFqn, operation);

        if (existing == null) {
            RecordReflection byId = getRecordReflectionById(defaultRecordToolId(simpleName, operation));
            if (byId != null && group.uuid().equals(byId.groupUuid())) {
                existing = byId;
            }
        }
        if (legacy == null) {
            Reflection byId = getHttpReflectionById(defaultRecordToolId(simpleName, operation));
            if (byId != null && group.uuid().equals(byId.groupUuid()) && isMandatoryRecordTool(byId)) {
                legacy = byId;
            }
        }

        String toolId = resolveRecordToolId(recordFqn, simpleName, operation,
                group.uuid(), existing == null ? null : existing.uuid(), legacy == null ? null : legacy.uuid());

        long now = System.currentTimeMillis();
        RecordReflection reflection = new RecordReflection(
                existing != null ? existing.uuid() : (legacy != null ? legacy.uuid() : UUID.randomUUID().toString()),
                toolId,
                name,
                description,
                group.uuid(),
                inputParameters,
                mandatoryRecordToolSchema(recordFqn, operation, simpleName),
                existing == null ? 1L : existing.version() + 1,
                existing == null ? now : existing.createdAt(),
                now);
        recordReflectionRepository.save(reflection);
        if (legacy != null) {
            reflectionRepository.delete(legacy.uuid());
        }
    }

    private RecordReflection getRecordReflectionById(String reflectionId) {
        if (reflectionId == null || reflectionId.isBlank()) {
            return null;
        }
        try (var stream = recordReflectionRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(reflection -> reflectionId.equals(reflection.id()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Reflection getHttpReflectionById(String reflectionId) {
        if (reflectionId == null || reflectionId.isBlank()) {
            return null;
        }
        try (var stream = reflectionRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(reflection -> reflectionId.equals(reflection.id()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private RecordReflection getRecordReflectionByMetadata(String recordFqn, String operation) {
        if (recordFqn == null || recordFqn.isBlank() || operation == null || operation.isBlank()) {
            return null;
        }
        try (var stream = recordReflectionRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(reflection -> recordToolMatches(reflection.outputSchema(), recordFqn, operation))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Reflection getHttpRecordReflectionByMetadata(String recordFqn, String operation) {
        if (recordFqn == null || recordFqn.isBlank() || operation == null || operation.isBlank()) {
            return null;
        }
        try (var stream = reflectionRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(this::isMandatoryRecordTool)
                    .filter(reflection -> recordToolMatches(reflection.outputSchema(), recordFqn, operation))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Reflection recordToReflection(RecordReflection recordReflection) {
        return new Reflection(
                recordReflection.uuid(),
                recordReflection.id(),
                recordReflection.name(),
                recordReflection.description(),
                recordReflection.groupUuid(),
                recordReflection.inputParameters(),
                "POST",
                "",
                Map.of(),
                Map.of(),
                "",
                CONTENT_TYPE_JSON,
                CONTENT_TYPE_JSON,
                recordReflection.outputSchema(),
                recordReflection.version(),
                recordReflection.createdAt(),
                recordReflection.updatedAt());
    }

    private String resolveRecordToolId(String recordFqn,
                                       String simpleName,
                                       String operation,
                                       String groupUuid,
                                       String allowedRecordUuid,
                                       String allowedLegacyUuid) {
        String base = defaultRecordToolId(simpleName, operation);
        if (isRecordToolIdAvailable(base, groupUuid, allowedRecordUuid, allowedLegacyUuid)) {
            return base;
        }
        String hashSuffix = Long.toString(Integer.toUnsignedLong((recordFqn + "#" + operation).hashCode()), 36)
                .toUpperCase(Locale.ROOT);
        String candidate = base + hashSuffix;
        if (isRecordToolIdAvailable(candidate, groupUuid, allowedRecordUuid, allowedLegacyUuid)) {
            return candidate;
        }

        int attempt = 2;
        while (attempt < 1000) {
            String indexed = candidate + attempt;
            if (isRecordToolIdAvailable(indexed, groupUuid, allowedRecordUuid, allowedLegacyUuid)) {
                return indexed;
            }
            attempt++;
        }
        throw new IllegalArgumentException("Unable to resolve unique record tool id for " + base + ".");
    }

    private boolean isRecordToolIdAvailable(String candidate,
                                            String targetGroupUuid,
                                            String allowedRecordUuid,
                                            String allowedLegacyUuid) {
        RecordReflection record = getRecordReflectionById(candidate);
        if (record != null) {
            if (allowedRecordUuid != null && allowedRecordUuid.equals(record.uuid())) {
                return true;
            }
            return targetGroupUuid != null && targetGroupUuid.equals(record.groupUuid());
        }

        Reflection legacy = getHttpReflectionById(candidate);
        if (legacy != null) {
            if (allowedLegacyUuid != null && allowedLegacyUuid.equals(legacy.uuid())) {
                return true;
            }
            return targetGroupUuid != null && targetGroupUuid.equals(legacy.groupUuid()) && isMandatoryRecordTool(legacy);
        }
        return true;
    }

    private static String defaultRecordToolId(String simpleName, String operation) {
        String baseName = toPascalIdentifier(simpleName);
        String prefix = switch (operation == null ? "" : operation.toUpperCase(Locale.ROOT)) {
            case "CREATE" -> "create";
            case "UPDATE" -> "update";
            case "READ" -> "get";
            case "DELETE" -> "delete";
            case "SEARCH" -> "search";
            default -> "record";
        };
        return prefix + baseName;
    }

    private static String toPascalIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "Record";
        }
        String compact = value.replaceAll("[^A-Za-z0-9]", "");
        if (compact.isBlank()) {
            return "Record";
        }
        return Character.toUpperCase(compact.charAt(0)) + compact.substring(1);
    }

    private boolean recordToolMatches(String outputSchema, String recordFqn, String operation) {
        if (outputSchema == null || outputSchema.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(outputSchema);
            if (!root.path(MANDATORY_RECORD_TOOL_MARKER).asBoolean(false)) {
                return false;
            }
            String schemaFqn = root.path("recordFqn").asText("").trim();
            String schemaOperation = root.path("operation").asText("").trim().toUpperCase(Locale.ROOT);
            return recordFqn.equals(schemaFqn) && operation.equalsIgnoreCase(schemaOperation);
        } catch (Exception ex) {
            return false;
        }
    }

    private String mandatoryRecordToolSchema(String recordFqn, String operation, String simpleName) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            payload.put("title", "Record " + operation + " Result - " + simpleName);
            payload.put("description", "Response schema for " + operation + " operation on " + simpleName + ".");
            payload.put("type", "object");
            payload.put(MANDATORY_RECORD_TOOL_MARKER, true);
            payload.put("recordFqn", recordFqn);
            payload.put("recordType", simpleName);
            payload.put("operation", operation);
            payload.put("immutable", true);
            payload.putAll(buildRecordOperationOutputSchema(operation));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"" + MANDATORY_RECORD_TOOL_MARKER + "\":true}";
        }
    }

    private Map<String, Object> buildRecordOperationOutputSchema(String operation) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        properties.put("status", Map.of("type", "string"));
        properties.put("operation", Map.of("type", "string"));
        required.add("status");
        required.add("operation");

        String normalized = operation == null ? "" : operation.toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "CREATE", "UPDATE" -> {
                properties.put("uuid", Map.of("type", "string"));
                properties.put("revision", Map.of("type", "integer"));
                properties.put("schemaVersion", Map.of("type", "integer"));
                required.add("uuid");
                required.add("revision");
                required.add("schemaVersion");
            }
            case "READ" -> {
                properties.put("record", Map.of("type", "object"));
                properties.put("revision", Map.of("type", "integer"));
                properties.put("schemaVersion", Map.of("type", "integer"));
                required.add("record");
            }
            case "DELETE" -> {
                properties.put("uuid", Map.of("type", "string"));
                required.add("uuid");
            }
            case "SEARCH" -> {
                properties.put("total", Map.of("type", "integer"));
                properties.put("page", Map.of("type", "integer"));
                properties.put("pageSize", Map.of("type", "integer"));
                Map<String, Object> items = new LinkedHashMap<>();
                items.put("type", "object");
                properties.put("results", Map.of("type", "array", "items", items));
                required.add("total");
                required.add("page");
                required.add("pageSize");
                required.add("results");
            }
            default -> {
                // Keep minimal status/operation schema for forward compatibility.
            }
        }

        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", true);
        return schema;
    }

    private boolean isMandatoryRecordTool(Reflection reflection) {
        if (reflection == null) {
            return false;
        }
        String outputSchema = reflection.outputSchema();
        if (outputSchema == null || outputSchema.isBlank()) {
            return false;
        }
        return marksMandatoryRecordTool(outputSchema);
    }

    private boolean marksMandatoryRecordTool(String outputSchema) {
        if (outputSchema == null || outputSchema.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(outputSchema);
            return root.path(MANDATORY_RECORD_TOOL_MARKER).asBoolean(false);
        } catch (Exception ex) {
            return outputSchema.contains("\"" + MANDATORY_RECORD_TOOL_MARKER + "\":true");
        }
    }

    private String executeRecordReflection(Reflection reflection,
                                           Map<String, Object> runtimeInputs,
                                           String bindingName,
                                           String username) {
        if (typeDatabaseService == null
                || javaTypeClassLoader == null
                || typeRecordBindingScopeRepository == null
                || typeRecordVersionMetadataRepository == null) {
            return jsonError("Record reflection engine is unavailable.");
        }

        ReflectionGroup group = reflectionGroupRepository.get(reflection.groupUuid());
        ReflectionBinding resolvedBinding;
        Map<String, Object> mergedInputs;
        try {
            resolvedBinding = resolveBindingForExecution(group, bindingName);
            Map<String, Object> cleanedRuntimeInputs = new LinkedHashMap<>(runtimeInputs == null ? Map.of() : runtimeInputs);
            cleanedRuntimeInputs.remove("bindingName");
            mergedInputs = applyBindingInputs(group == null ? List.of() : group.bindingParameters(), resolvedBinding, cleanedRuntimeInputs);
        } catch (Exception ex) {
            return jsonError(ex.getMessage());
        }

        List<String> missing = new ArrayList<>();
        for (ReflectionInputParameter parameter : reflection.inputParameters()) {
            if (!parameter.required()) {
                continue;
            }
            Object value = mergedInputs.get(parameter.name());
            if (value == null || String.valueOf(value).isBlank()) {
                missing.add(parameter.name());
            }
        }
        if (!missing.isEmpty()) {
            return jsonMissing(missing);
        }

        RecordToolMetadata metadata = parseRecordToolMetadata(reflection);
        if (metadata == null) {
            return jsonError("Record reflection metadata is invalid or missing.");
        }

        Class<?> entityClass;
        try {
            entityClass = javaTypeClassLoader.loadClass(metadata.recordFqn());
        } catch (ClassNotFoundException ex) {
            return jsonError("Record type not found: " + metadata.recordFqn());
        }

        try {
            return switch (metadata.operation()) {
                case "CREATE", "UPDATE" -> executeRecordSave(metadata.recordFqn(), entityClass, mergedInputs, resolvedBinding);
                case "READ" -> executeRecordRead(metadata.recordFqn(), entityClass, mergedInputs, resolvedBinding);
                case "DELETE" -> executeRecordDelete(metadata.recordFqn(), entityClass, mergedInputs, resolvedBinding);
                case "SEARCH" -> executeRecordSearch(metadata.recordFqn(), entityClass, mergedInputs, resolvedBinding);
                default -> jsonError("Unsupported record operation: " + metadata.operation());
            };
        } catch (Exception ex) {
            return jsonError(ex.getMessage());
        }
    }

    private String executeRecordSave(String recordFqn,
                                     Class<?> entityClass,
                                     Map<String, Object> inputs,
                                     ReflectionBinding binding) throws Exception {
        if (binding == null || binding.uuid() == null || binding.uuid().isBlank()) {
            return jsonError("Binding context is required for record write operations.");
        }

        Map<String, Object> filtered = filterRecordFields(entityClass, inputs);
        Object entity = objectMapper.convertValue(filtered, entityClass);
        String entityUuid = entityUuid(entity);
        if (entityUuid == null || entityUuid.isBlank()) {
            return jsonError("Record uuid is required.");
        }

        String scopeId = recordScopeId(recordFqn, entityUuid);
        TypeRecordBindingScope existingScope = typeRecordBindingScopeRepository.get(scopeId);
        if (existingScope != null && !binding.uuid().equals(existingScope.bindingUuid())) {
            return jsonError("Record belongs to a different binding scope.");
        }

        String versionId = recordVersionId(recordFqn, entityUuid);
        TypeRecordVersionMetadata existingVersion = typeRecordVersionMetadataRepository.get(versionId);

        long expectedRevision = asLong(inputs.get("expectedRevision"), 0L);
        if (expectedRevision > 0) {
            if (existingVersion == null) {
                if (expectedRevision != 1L) {
                    return jsonError("Revision mismatch. Record does not exist at expectedRevision=" + expectedRevision + ".");
                }
            } else if (existingVersion.entityRevision() != expectedRevision) {
                return jsonError("Revision mismatch. expected=" + expectedRevision + ", actual=" + existingVersion.entityRevision() + ".");
            }
        }

        typeDatabaseService.save(entity);

        long now = System.currentTimeMillis();
        typeRecordBindingScopeRepository.save(new TypeRecordBindingScope(
                scopeId,
                recordFqn,
                entityUuid,
                binding.uuid(),
                binding.name(),
                existingScope == null ? now : existingScope.createdAt(),
                now));

            long schemaVersion = resolveSchemaVersion(recordFqn, now);
            long revision = existingVersion == null ? 1L : existingVersion.entityRevision() + 1L;
            typeRecordVersionMetadataRepository.save(new TypeRecordVersionMetadata(
                versionId,
                recordFqn,
                entityUuid,
                schemaVersion,
                revision,
                existingVersion == null ? binding.uuid() : existingVersion.createdByBindingUuid(),
                binding.uuid(),
                existingVersion == null ? now : existingVersion.createdAt(),
                now));

        return objectMapper.writeValueAsString(Map.of(
                "status", "ok",
                "operation", "save",
                "uuid", entityUuid,
                "revision", revision,
                "schemaVersion", schemaVersion));
    }

    private String executeRecordRead(String recordFqn,
                                     Class<?> entityClass,
                                     Map<String, Object> inputs,
                                     ReflectionBinding binding) throws Exception {
        String uuid = asString(inputs.get("uuid"));
        if (uuid.isBlank()) {
            return jsonError("uuid is required.");
        }
        if (!canAccessRecordScope(recordFqn, uuid, binding)) {
            return jsonError("Record is not accessible in this binding scope.");
        }

        Object entity = typeDatabaseService.get(entityClass, uuid);
        if (entity == null) {
            return objectMapper.writeValueAsString(Map.of("status", "not_found", "uuid", uuid));
        }

        TypeRecordVersionMetadata version = typeRecordVersionMetadataRepository.get(recordVersionId(recordFqn, uuid));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("operation", "read");
        response.put("record", entity);
        if (version != null) {
            response.put("revision", version.entityRevision());
            response.put("schemaVersion", version.schemaVersion());
        }
        return objectMapper.writeValueAsString(response);
    }

    private String executeRecordDelete(String recordFqn,
                                       Class<?> entityClass,
                                       Map<String, Object> inputs,
                                       ReflectionBinding binding) throws Exception {
        String uuid = asString(inputs.get("uuid"));
        if (uuid.isBlank()) {
            return jsonError("uuid is required.");
        }
        if (!canAccessRecordScope(recordFqn, uuid, binding)) {
            return jsonError("Record is not accessible in this binding scope.");
        }

        typeDatabaseService.delete(entityClass, uuid);
        typeRecordBindingScopeRepository.delete(recordScopeId(recordFqn, uuid));
        typeRecordVersionMetadataRepository.delete(recordVersionId(recordFqn, uuid));

        return objectMapper.writeValueAsString(Map.of(
                "status", "ok",
                "operation", "delete",
                "uuid", uuid));
    }

    private String executeRecordSearch(String recordFqn,
                                       Class<?> entityClass,
                                       Map<String, Object> inputs,
                                       ReflectionBinding binding) throws Exception {
        String query = asString(inputs.get("query"));
        if (query.isBlank()) {
            return jsonError("query is required.");
        }

        String queryType = asString(inputs.get("queryType"));
        if (queryType.isBlank()) {
            queryType = "SQL";
        }
        String sortField = asString(inputs.get("sortField"));
        if (sortField.isBlank()) {
            sortField = "uuid";
        }
        String sortOrderRaw = asString(inputs.get("sortOrder"));
        SortOrder sortOrder = "DESC".equalsIgnoreCase(sortOrderRaw) ? SortOrder.DESC : SortOrder.ASC;
        int page = asInt(inputs.get("page"), 0);
        int pageSize = asInt(inputs.get("pageSize"), 20);

        List<Object> filtered = new ArrayList<>();
        boolean mongo = "MONGO".equalsIgnoreCase(queryType);
        try (var stream = mongo
                ? typeDatabaseService.searchByMongoFilter(entityClass, query, 0, Integer.MAX_VALUE, sortField, sortOrder)
                : typeDatabaseService.searchBySql(entityClass, query, 0, Integer.MAX_VALUE, sortField, sortOrder)) {
            stream.forEach(entity -> {
                String uuid = entityUuid(entity);
                if (uuid != null && canAccessRecordScope(recordFqn, uuid, binding)) {
                    filtered.add(entity);
                }
            });
        }

        int from = Math.max(0, page * pageSize);
        List<Object> pageItems;
        if (from >= filtered.size()) {
            pageItems = List.of();
        } else {
            int to = Math.min(filtered.size(), from + pageSize);
            pageItems = List.copyOf(filtered.subList(from, to));
        }

        return objectMapper.writeValueAsString(Map.of(
                "status", "ok",
                "operation", "search",
                "total", filtered.size(),
                "page", page,
                "pageSize", pageSize,
                "results", pageItems));
    }

    private boolean canAccessRecordScope(String recordFqn, String recordUuid, ReflectionBinding binding) {
        if (binding == null || binding.uuid() == null || binding.uuid().isBlank()) {
            return false;
        }
        TypeRecordBindingScope scope = typeRecordBindingScopeRepository.get(recordScopeId(recordFqn, recordUuid));
        return scope != null && binding.uuid().equals(scope.bindingUuid());
    }

    private RecordToolMetadata parseRecordToolMetadata(Reflection reflection) {
        if (reflection == null || reflection.outputSchema() == null || reflection.outputSchema().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(reflection.outputSchema());
            if (!root.path(MANDATORY_RECORD_TOOL_MARKER).asBoolean(false)) {
                return null;
            }
            String recordFqn = root.path("recordFqn").asText("").trim();
            String operation = root.path("operation").asText("").trim().toUpperCase(Locale.ROOT);
            if (recordFqn.isBlank() || operation.isBlank()) {
                return null;
            }
            return new RecordToolMetadata(recordFqn, operation);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String recordScopeId(String recordFqn, String recordUuid) {
        return recordFqn + "::" + recordUuid;
    }

    private static String recordVersionId(String recordFqn, String recordUuid) {
        return recordFqn + "::" + recordUuid;
    }

    private long resolveSchemaVersion(String recordFqn, long fallback) {
        if (javaTypeRepository == null || recordFqn == null || recordFqn.isBlank()) {
            return fallback;
        }
        JavaType javaType = javaTypeRepository.get(recordFqn);
        if (javaType == null || javaType.updatedAt() <= 0) {
            return fallback;
        }
        return javaType.updatedAt();
    }

    private Map<String, Object> filterRecordFields(Class<?> entityClass, Map<String, Object> inputs) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        if (entityClass == null || !entityClass.isRecord() || inputs == null) {
            return filtered;
        }
        for (var component : entityClass.getRecordComponents()) {
            if (component == null || component.getName() == null || component.getName().isBlank()) {
                continue;
            }
            String field = component.getName();
            if (inputs.containsKey(field)) {
                filtered.put(field, inputs.get(field));
            }
        }
        return filtered;
    }

    private static String entityUuid(Object entity) {
        if (entity == null) {
            return null;
        }
        try {
            Object value = entity.getClass().getMethod("uuid").invoke(entity);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int asInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long asLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record RecordToolMetadata(String recordFqn, String operation) {}

    private boolean isGroupToolIdAvailable(String candidate, String excludeGroupUuid) {
        String normalizedCandidate = ToolIdGenerator.normalizeBase(candidate, "group");
        try (var stream = reflectionGroupRepository.list(0, Integer.MAX_VALUE)) {
            return stream.noneMatch(group -> {
                if (excludeGroupUuid != null && excludeGroupUuid.equals(group.uuid())) {
                    return false;
                }
                String existing = ToolIdGenerator.normalizeBase(group.toolId(), "group");
                return normalizedCandidate.equals(existing);
            });
        }
    }

    private String jsonMissing(List<String> missing) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "missing_parameters");
            payload.put("missing", missing);
            payload.put("message", "Required parameters missing: " + String.join(", ", missing));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"status\":\"error\",\"message\":\"Required parameters missing\"}";
        }
    }

    private String jsonError(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "message", message == null ? "Unknown error" : message,
                    "retryAllowed", false,
                    "instruction", "Report this reflection error to the user and do not retry with different binding or profile names."));
        } catch (Exception ex) {
            return "{\"status\":\"error\",\"message\":\"Unknown error\",\"retryAllowed\":false,\"instruction\":\"Report this reflection error to the user and do not retry with different binding or profile names.\"}";
        }
    }

    private static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        String trimmed = value.trim();
        if (!IDENTITY_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(field + " must be alphanumeric and 3-64 characters.");
        }
        return trimmed;
    }

    private static String identityOrDefault(String value, String seed, String fallback) {
        if (value != null && !value.isBlank()) {
            return requireIdentity(value, fallback);
        }
        String compact = seed == null ? "" : seed.replaceAll("[^A-Za-z0-9]", "");
        if (compact.length() < 3) {
            compact = fallback;
        }
        if (compact.length() > 64) {
            compact = compact.substring(0, 64);
        }
        return compact;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }

        public record ReflectionGroupRequest(
            String name,
            String description,
            String type,
            String baseUrl,
            Boolean urlOverrideEnabled,
            List<SkillSecret> bindingSecrets,
            List<ReflectionBindingParameter> bindingParameters,
            String authenticationMode,
            String oauthTemplateId,
            String groupId,
            String artifactId) {

            public ReflectionGroupRequest(String name,
                                          String description,
                                          String type,
                                          String baseUrl,
                                          Boolean urlOverrideEnabled,
                                          List<ReflectionBindingParameter> bindingParameters,
                                          List<SkillSecret> bindingSecrets,
                                          String authenticationMode,
                                          String oauthTemplateId) {
                this(name, description, type, baseUrl, urlOverrideEnabled, bindingSecrets, bindingParameters,
                        authenticationMode, oauthTemplateId, "", "");
            }

            public ReflectionGroupRequest(String name,
                                          String description,
                                          String type,
                                          String baseUrl,
                                          Boolean urlOverrideEnabled,
                                          List<SkillSecret> bindingSecrets,
                                          List<ReflectionBindingParameter> bindingParameters) {
                this(name, description, type, baseUrl, urlOverrideEnabled, bindingSecrets, bindingParameters, "NONE", "", "", "");
            }

            public ReflectionGroupRequest(String name,
                                          String description,
                                          String type,
                                          String baseUrl,
                                          List<SkillSecret> bindingSecrets,
                                          List<ReflectionBindingParameter> bindingParameters) {
                this(name, description, type, baseUrl, true, bindingSecrets, bindingParameters, "NONE", "", "", "");
            }
        }

        public record ReflectionBindingRequest(
            String name,
            String reflectionUuid,
            String baseUrl,
            Map<String, String> parameterValues,
            Map<String, String> secretValues,
            String copySecretsFromBindingName) {

            public ReflectionBindingRequest(String name,
                                            String reflectionUuid,
                                            String baseUrl,
                                            Map<String, String> parameterValues,
                                            Map<String, String> secretValues) {
                this(name, reflectionUuid, baseUrl, parameterValues, secretValues, null);
            }

            public ReflectionBindingRequest(String name,
                                            String baseUrl,
                                            Map<String, String> parameterValues,
                                            Map<String, String> secretValues) {
                this(name, null, baseUrl, parameterValues, secretValues, null);
            }
        }

    public record ReflectionRequest(
            String id,
            String name,
            String description,
            String groupUuid,
            List<ReflectionInputParameter> inputParameters,
            String method,
            String url,
            Map<String, String> headers,
            Map<String, String> queryParameters,
            String bodyTemplate,
            String requestContentType,
            String responseContentType,
            String outputSchema
    ) {}

    public record GroupDeleteResult(boolean ok, String message) {}

    public record BindingSaveOutcome(
            String status,
            String message,
            String authorizationUrl,
            ReflectionBinding binding) {}

    public record PendingOAuthBindingCompletion(
            boolean handled,
            boolean success,
            String message,
            String groupUuid,
            String bindingName) {

        static PendingOAuthBindingCompletion notHandled() {
            return new PendingOAuthBindingCompletion(false, false, "", null, null);
        }

        static PendingOAuthBindingCompletion succeeded(String groupUuid, String bindingName) {
            return new PendingOAuthBindingCompletion(true, true, "", groupUuid, bindingName);
        }

        static PendingOAuthBindingCompletion failed(String groupUuid, String bindingName, String message) {
            return new PendingOAuthBindingCompletion(true, false, message, groupUuid, bindingName);
        }
    }

            public record ReflectionGroupExportPackage(
                String vorkReflectionGroupExport,
                ReflectionGroup group,
                List<Reflection> reflections) {}

            public record ReflectionGroupImportResult(
                String status,
                String groupUuid,
                List<String> importedReflectionUuids,
                String message) {}
}
