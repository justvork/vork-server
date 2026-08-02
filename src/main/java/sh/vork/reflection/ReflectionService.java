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
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.security.SkillSecretSubstitutor;
import sh.vork.oauth.OAuthClientService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.security.SecureCredentialStore;
import sh.vork.skill.SkillSecret;

@Service
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);

    private static final Pattern REFLECTION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern BINDING_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Set<String> METHODS_WITHOUT_BODY = Set.of("GET", "DELETE", "HEAD", "OPTIONS");
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

    private final DatabaseRepository<Reflection> reflectionRepository;
    private final DatabaseRepository<ReflectionGroup> reflectionGroupRepository;
    private final DatabaseRepository<ReflectionBinding> reflectionBindingRepository;
    private final OAuthClientService oauthClientService;
    private final SkillSecretSubstitutor skillSecretSubstitutor;
    private final SecureCredentialStore secureCredentialStore;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public ReflectionService(RepositoryFactory factory,
                             OAuthClientService oauthClientService,
                             SkillSecretSubstitutor skillSecretSubstitutor,
                             SecureCredentialStore secureCredentialStore,
                             ObjectMapper objectMapper) {
        this(
                factory.create(Reflection.class),
                factory.create(ReflectionGroup.class),
                factory.create(ReflectionBinding.class),
                oauthClientService,
                skillSecretSubstitutor,
                secureCredentialStore,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    ReflectionService(DatabaseRepository<Reflection> reflectionRepository,
                      DatabaseRepository<ReflectionGroup> reflectionGroupRepository,
                      DatabaseRepository<ReflectionBinding> reflectionBindingRepository,
                      OAuthClientService oauthClientService,
                      SkillSecretSubstitutor skillSecretSubstitutor,
                      SecureCredentialStore secureCredentialStore,
                      ObjectMapper objectMapper,
                      HttpClient httpClient) {
        this.reflectionRepository = reflectionRepository;
        this.reflectionGroupRepository = reflectionGroupRepository;
        this.reflectionBindingRepository = reflectionBindingRepository;
        this.oauthClientService = oauthClientService;
        this.skillSecretSubstitutor = skillSecretSubstitutor;
        this.secureCredentialStore = secureCredentialStore;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
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

    public ReflectionGroup createGroup(ReflectionGroupRequest request) {
        log.debug("ENTER createGroup: [name={}]", request == null ? "null" : request.name());
        if (request == null) {
            throw new IllegalArgumentException("Group payload is required.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Group name is required.");
        }
        ReflectionType type = parseGroupType(request.type());
        long now = System.currentTimeMillis();
        ReflectionGroup group = new ReflectionGroup(
                UUID.randomUUID().toString(),
                request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                type,
            request.baseUrl() == null ? "" : request.baseUrl().trim(),
            normalizeBindingSecrets(request.bindingSecrets()),
            normalizeBindingParameters(request.bindingParameters()),
                1L,
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
        ReflectionGroup updated = new ReflectionGroup(
                existing.uuid(),
                request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                type,
            request.baseUrl() == null ? "" : request.baseUrl().trim(),
            normalizeBindingSecrets(request.bindingSecrets()),
            normalizeBindingParameters(request.bindingParameters()),
                existing.version() + 1,
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
                    .filter(binding -> groupUuid.equals(binding.groupUuid()))
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

    public ReflectionBinding createBinding(String username, String groupUuid, ReflectionBindingRequest request) {
        ReflectionGroup group = requireGroup(groupUuid);
        requireUsername(username);
        ReflectionBinding normalized = normalizeBindingRequest(null, group, request);
        enforceDefaultBindingInvariant(groupUuid, null, normalized.name());
        reflectionBindingRepository.save(normalized);
        saveBindingSecrets(
                username,
                normalized,
                group.bindingSecrets(),
                mergeSecretValuesFromSourceBinding(username, groupUuid, request));
        log.info("Reflection binding created [groupUuid={}, name={}]", groupUuid, normalized.name());
        return normalized;
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
        reflectionBindingRepository.save(normalized);
        saveBindingSecrets(username, normalized, group.bindingSecrets(), request.secretValues());
        log.info("Reflection binding updated [groupUuid={}, name={}]", groupUuid, normalized.name());
        return normalized;
    }

    public boolean deleteBinding(String username, String groupUuid, String bindingName) {
        requireUsername(username);
        ReflectionBinding existing = getBinding(groupUuid, bindingName);
        if (existing == null) {
            return false;
        }
        if ("default".equalsIgnoreCase(existing.name())) {
            throw new IllegalArgumentException("Default binding cannot be deleted.");
        }
        reflectionBindingRepository.delete(existing.uuid());
        clearBindingSecrets(username, existing);
        log.info("Reflection binding deleted [groupUuid={}, name={}]", groupUuid, bindingName);
        return true;
    }

    public List<Reflection> listReflections() {
        log.debug("ENTER listReflections");
        try (var stream = reflectionRepository.list(0, Integer.MAX_VALUE)) {
            return stream.sorted(Comparator.comparing(
                    reflection -> reflection.id() == null ? "" : reflection.id(),
                    String.CASE_INSENSITIVE_ORDER)).toList();
        }
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
        return reflectionRepository.get(uuid);
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

    public Reflection updateReflection(String uuid, ReflectionRequest request) {
        log.debug("ENTER updateReflection: [uuid={}]", uuid);
        Reflection existing = reflectionRepository.get(uuid);
        if (existing == null) {
            return null;
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
            return false;
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
                group.name(),
                group.description(),
                group.type(),
            group.baseUrl(),
            group.bindingSecrets(),
            group.bindingParameters(),
                group.version(),
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
        if (reflectionGroupRepository.get(incomingGroup.uuid()) != null) {
            return new ReflectionGroupImportResult(
                    "already_installed",
                    incomingGroup.uuid(),
                    List.of(),
                    "Reflection group '" + incomingGroup.name() + "' is already installed.");
        }

        List<Reflection> incomingReflections = pkg.reflections().stream()
                .filter(reflection -> reflection != null && reflection.uuid() != null && !reflection.uuid().isBlank())
                .toList();
        if (incomingReflections.isEmpty()) {
            return new ReflectionGroupImportResult("error", incomingGroup.uuid(), List.of(), "No valid reflections in package.");
        }

        for (Reflection reflection : incomingReflections) {
            if (reflectionRepository.get(reflection.uuid()) != null) {
                return new ReflectionGroupImportResult(
                        "already_installed",
                        incomingGroup.uuid(),
                        List.of(),
                        "Reflection with UUID '" + reflection.uuid() + "' is already installed.");
            }

            Reflection conflictById = getReflectionById(reflection.id());
            if (conflictById != null) {
                return new ReflectionGroupImportResult(
                        "error",
                        incomingGroup.uuid(),
                        List.of(),
                        "Reflection id '" + reflection.id() + "' already exists.");
            }
        }

        long now = System.currentTimeMillis();
        ReflectionGroup normalizedGroup = new ReflectionGroup(
                incomingGroup.uuid(),
                incomingGroup.name(),
                incomingGroup.description(),
                incomingGroup.type() == null ? ReflectionType.REST : incomingGroup.type(),
            incomingGroup.baseUrl() == null ? "" : incomingGroup.baseUrl(),
            normalizeBindingSecrets(incomingGroup.bindingSecrets()),
            normalizeBindingParameters(incomingGroup.bindingParameters()),
                incomingGroup.version() < 1 ? 1 : incomingGroup.version(),
                incomingGroup.createdAt() > 0 ? incomingGroup.createdAt() : now,
                incomingGroup.updatedAt() > 0 ? incomingGroup.updatedAt() : now);
        reflectionGroupRepository.save(normalizedGroup);

        List<Reflection> normalizedReflections = incomingReflections.stream()
                .map(reflection -> normalizeImportedReflection(reflection, normalizedGroup.uuid()))
                .toList();
        for (Reflection reflection : normalizedReflections) {
            reflectionRepository.save(reflection);
        }

        List<String> importedUuids = normalizedReflections.stream().map(Reflection::uuid).toList();
        return new ReflectionGroupImportResult("imported", normalizedGroup.uuid(), importedUuids, null);
    }

    public String executeRestReflection(String reflectionId,
                                        Map<String, Object> runtimeInputs,
                                        String bindingName,
                                        String username) {
        Reflection reflection = getReflectionById(reflectionId);
        if (reflection == null) {
            return jsonError("Reflection not found: " + reflectionId);
        }

        ReflectionGroup group = reflectionGroupRepository.get(reflection.groupUuid());
        ReflectionType type = group == null ? ReflectionType.REST : group.type();
        if (type != ReflectionType.REST) {
            return jsonError("Only REST reflections are executable at this time.");
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

            String rawUrl = applyTemplate(reflection.url(), stringInputs);
            rawUrl = resolveRequestUrl(rawUrl, group, resolvedBinding);
            rawUrl = substituteSecrets(rawUrl, username, bindingSecretValues);
            URI requestUri = buildUri(rawUrl, reflection.queryParameters(), stringInputs, username, bindingSecretValues);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "vork-reflection/1.0");

                Map<String, String> resolvedHeaders = resolveHeaders(
                    reflection.headers(), stringInputs, username, bindingSecretValues);

                String body = resolveBody(
                    reflection,
                    method,
                    stringInputs,
                    mergedInputs,
                    username,
                    bindingSecretValues,
                    requestContentType);
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
            if (responseBody.length() > 20_000) {
                responseBody = responseBody.substring(0, 20_000) + "\n...<truncated>";
            }

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
            throw new IllegalArgumentException("Only REST reflection groups are supported at this time.");
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
                    parameter.required()));
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
        try {
            return ReflectionType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unsupported reflection group type: " + rawType);
        }
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

    private Map<String, String> resolveHeaders(Map<String, String> headerTemplates,
                                               Map<String, String> inputValues,
                                               String username,
                                               Map<String, String> bindingSecretValues) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (headerTemplates == null || headerTemplates.isEmpty()) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : headerTemplates.entrySet()) {
            String value = applyTemplate(entry.getValue(), inputValues);
            value = substituteSecrets(value, username, bindingSecretValues);
            value = oauthClientService.resolveHeaderValue(username, value);
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    private URI buildUri(String rawUrl,
                         Map<String, String> baseQueryParams,
                         Map<String, String> inputValues,
                         String username,
                         Map<String, String> bindingSecretValues) {
        StringBuilder url = new StringBuilder(rawUrl == null ? "" : rawUrl.trim());
        boolean hasQuery = url.indexOf("?") >= 0;

        Map<String, String> merged = new LinkedHashMap<>();
        if (baseQueryParams != null) {
            for (Map.Entry<String, String> entry : baseQueryParams.entrySet()) {
                String resolvedValue = applyTemplate(entry.getValue(), inputValues);
                resolvedValue = substituteSecrets(resolvedValue, username, bindingSecretValues);
                merged.put(entry.getKey(), resolvedValue);
            }
        }
        for (Map.Entry<String, String> entry : inputValues.entrySet()) {
            if (!merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
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
        return URI.create(url.toString());
    }

    private String resolveBody(Reflection reflection,
                               String method,
                               Map<String, String> stringInputs,
                               Map<String, Object> rawInputs,
                               String username,
                               Map<String, String> bindingSecretValues,
                               String requestContentType) throws Exception {
        if (METHODS_WITHOUT_BODY.contains(method)) {
            return null;
        }

        String bodyTemplate = reflection.bodyTemplate();
        if (bodyTemplate != null && !bodyTemplate.isBlank()) {
            String body = applyBodyTemplate(bodyTemplate, stringInputs, requestContentType);
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
                generated.put(name, coerceByType(rawInputs.get(name), parameter.type()));
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

    private static String applyBodyTemplate(String template,
                                            Map<String, String> params,
                                            String requestContentType) {
        if (template == null || template.isBlank() || params == null || params.isEmpty()) {
            return template == null ? "" : template;
        }

        String resolved = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            String encoded = encodeTemplateValue(value, requestContentType);
            resolved = resolved.replace("{{" + entry.getKey() + "}}", encoded);
        }
        return resolved;
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
        long now = System.currentTimeMillis();
        if (existing == null) {
            return new ReflectionBinding(
                    UUID.randomUUID().toString(),
                    group.uuid(),
                    name,
                    request.baseUrl() == null ? "" : request.baseUrl().trim(),
                    parameterValues,
                    1L,
                    now,
                    now);
        }

        return new ReflectionBinding(
                existing.uuid(),
                group.uuid(),
                name,
                request.baseUrl() == null ? "" : request.baseUrl().trim(),
                parameterValues,
                existing.version() + 1,
                existing.createdAt(),
                now);
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
            secureCredentialStore.saveSecretForUser(username, bindingSecretStorageKey(binding, key), entry.getValue());
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

            String sourceValue = secureCredentialStore.getSecretForUser(
                    username,
                    bindingSecretStorageKey(source, secretName));
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
        return "REFLECTION_BINDING:" + binding.groupUuid() + ":" + binding.name() + ":" + normalizedSecretName;
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
            String value = secureCredentialStore.getSecretForUser(username, bindingSecretStorageKey(binding, name));
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
                                            ReflectionBinding binding) {
        String url = reflectionUrl == null ? "" : reflectionUrl.trim();
        if (url.isBlank()) {
            return url;
        }
        if (isAbsoluteUrl(url)) {
            return url;
        }

        String baseUrl = "";
        if (binding != null && binding.baseUrl() != null && !binding.baseUrl().isBlank()) {
            baseUrl = binding.baseUrl().trim();
        } else if (group.baseUrl() != null && !group.baseUrl().isBlank()) {
            baseUrl = group.baseUrl().trim();
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
        try {
            URI uri = URI.create(url);
            return uri.getScheme() != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String applyTemplate(String template, Map<String, String> params) {
        if (template == null || template.isBlank() || params == null || params.isEmpty()) {
            return template == null ? "" : template;
        }
        String resolved = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            resolved = resolved.replace("{{" + entry.getKey() + "}}", value);
        }
        return resolved;
    }

    private Reflection normalizeImportedReflection(Reflection reflection, String groupUuid) {
        long now = System.currentTimeMillis();
        return new Reflection(
                reflection.uuid(),
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
                reflection.version() < 1 ? 1 : reflection.version(),
                reflection.createdAt() > 0 ? reflection.createdAt() : now,
                reflection.updatedAt() > 0 ? reflection.updatedAt() : now);
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
                    "message", message == null ? "Unknown error" : message));
        } catch (Exception ex) {
            return "{\"status\":\"error\",\"message\":\"Unknown error\"}";
        }
    }

        public record ReflectionGroupRequest(
            String name,
            String description,
            String type,
            String baseUrl,
            List<SkillSecret> bindingSecrets,
            List<ReflectionBindingParameter> bindingParameters) {}

        public record ReflectionBindingRequest(
            String name,
            String baseUrl,
            Map<String, String> parameterValues,
            Map<String, String> secretValues,
            String copySecretsFromBindingName) {

            public ReflectionBindingRequest(String name,
                                            String baseUrl,
                                            Map<String, String> parameterValues,
                                            Map<String, String> secretValues) {
                this(name, baseUrl, parameterValues, secretValues, null);
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
            String requestContentType
    ) {}

    public record GroupDeleteResult(boolean ok, String message) {}

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
