package sh.vork.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SortOrder;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.SqlParseException;
import sh.vork.typegen.TypeDatabaseService;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dynamic binding provider for runtime record entities.
 */
@Service
public class RecordBindingProvider implements BindingProvider {

    private static final String PROVIDER_ID = "record";
    private static final String DEFAULT_PROFILE = "default";

    private final TypeDatabaseService typeDatabaseService;
    private final JavaTypeClassLoader classLoader;
    private final DatabaseRepository<JavaType> javaTypeRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public RecordBindingProvider(TypeDatabaseService typeDatabaseService,
                                 JavaTypeClassLoader classLoader,
                                 DatabaseRepository<JavaType> javaTypeRepository,
                                 ObjectMapper objectMapper) {
        this.typeDatabaseService = typeDatabaseService;
        this.classLoader = classLoader;
        this.javaTypeRepository = javaTypeRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<BindingSummary> listBindings() {
        Map<String, String> fqnByBindingId = new LinkedHashMap<>();
        List<BindingSummary> result = new ArrayList<>();

        for (Class<?> recordClass : discoverRecordEntityClasses()) {
            String bindingId = bindingIdFor(recordClass);
            String existing = fqnByBindingId.putIfAbsent(bindingId, recordClass.getName());
            if (existing != null && !existing.equals(recordClass.getName())) {
                throw new IllegalStateException("Record binding id collision: " + bindingId
                        + " (" + existing + " vs " + recordClass.getName() + ")");
            }
            result.add(new BindingSummary(
                    bindingId,
                    recordClass.getSimpleName(),
                    PROVIDER_ID,
                    List.of(DEFAULT_PROFILE),
                    "Dynamic record binding for " + recordClass.getName()));
        }

        result.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return result;
    }

    @Override
    public List<BindingOperationContract> listOperationContracts(String bindingId, String profile) {
        requireDefaultProfile(profile);
        Class<?> recordClass = resolveRecordClass(bindingId);
        Map<String, Object> recordSchema = buildRecordSchema(recordClass);

        List<BindingOperationContract> contracts = new ArrayList<>();
        contracts.add(new BindingOperationContract(
                "create",
                "create",
                "Create a new " + recordClass.getSimpleName() + " record.",
                recordSchema,
                recordSchema,
                "application/json"));

        contracts.add(new BindingOperationContract(
                "read",
                "read",
                "Read a " + recordClass.getSimpleName() + " record by id.",
                idInputSchema(),
                recordSchema,
                "application/json"));

        contracts.add(new BindingOperationContract(
                "update",
                "update",
                "Update an existing " + recordClass.getSimpleName() + " record.",
                recordSchema,
                recordSchema,
                "application/json"));

        contracts.add(new BindingOperationContract(
                "delete",
                "delete",
                "Delete a " + recordClass.getSimpleName() + " record by id.",
                idInputSchema(),
                Map.of(
                        "type", "object",
                        "properties", Map.of("deleted", Map.of("type", "boolean")),
                        "required", List.of("deleted")),
                "application/json"));

        contracts.add(new BindingOperationContract(
                "search",
                "search",
                "Search " + recordClass.getSimpleName() + " records with paging.",
                searchInputSchema(),
                searchOutputSchema(recordClass),
                "application/json"));

        contracts.add(new BindingOperationContract(
            "count",
            "count",
            "Count " + recordClass.getSimpleName() + " records (optionally filtered by query).",
            countInputSchema(),
            countOutputSchema(),
            "application/json"));

        return contracts;
    }

    @Override
    public BindingInvocationResult invoke(BindingInvocationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        requireDefaultProfile(request.profile());

        Class<?> recordClass = resolveRecordClass(request.bindingId());
        String operation = (request.operationId() == null ? "" : request.operationId().trim().toLowerCase(Locale.ROOT));
        Map<String, Object> args = request.args() == null ? Map.of() : request.args();

        return switch (operation) {
            case "create" -> invokeCreate(recordClass, args);
            case "read" -> invokeRead(recordClass, args);
            case "update" -> invokeUpdate(recordClass, args);
            case "delete" -> invokeDelete(recordClass, args);
            case "search" -> invokeSearch(recordClass, args);
            case "count" -> invokeCount(recordClass, args);
            default -> throw new IllegalArgumentException("Unsupported record binding operation: " + request.operationId());
        };
    }

    private BindingInvocationResult invokeCreate(Class<?> recordClass, Map<String, Object> args) {
        Object entity = objectMapper.convertValue(args, recordClass);
        typeDatabaseService.save(entity);
        return new BindingInvocationResult(200, entity, "application/json");
    }

    private BindingInvocationResult invokeRead(Class<?> recordClass, Map<String, Object> args) {
        String id = resolveId(args);
        Object entity = typeDatabaseService.get(recordClass, id);
        if (entity == null) {
            return new BindingInvocationResult(404, Map.of("status", "error", "message", "Record not found"), "application/json");
        }
        return new BindingInvocationResult(200, entity, "application/json");
    }

    private BindingInvocationResult invokeUpdate(Class<?> recordClass, Map<String, Object> args) {
        Object entity = objectMapper.convertValue(args, recordClass);
        typeDatabaseService.save(entity);
        return new BindingInvocationResult(200, entity, "application/json");
    }

    private BindingInvocationResult invokeDelete(Class<?> recordClass, Map<String, Object> args) {
        String id = resolveId(args);
        typeDatabaseService.delete(recordClass, id);
        return new BindingInvocationResult(200, Map.of("deleted", true), "application/json");
    }

    private BindingInvocationResult invokeSearch(Class<?> recordClass, Map<String, Object> args) {
        int page = intArg(args, "page", 0);
        int size = intArg(args, "size", 20);
        String sort = stringArg(args, "sort", "uuid");
        String direction = stringArg(args, "direction", "ASC");
        String query = stringArg(args, "query", "");

        SortOrder order = "DESC".equalsIgnoreCase(direction) ? SortOrder.DESC : SortOrder.ASC;

        List<Object> content = new ArrayList<>();
        long total;
        if (query.isBlank()) {
            try (var stream = typeDatabaseService.list(recordClass, page, size)) {
                stream.forEach(content::add);
            }
            total = typeDatabaseService.count(recordClass);
        } else {
            try (var stream = typeDatabaseService.searchBySql(recordClass, query, page, size, sort, order)) {
                stream.forEach(content::add);
            } catch (SqlParseException ex) {
                return new BindingInvocationResult(400,
                        Map.of("status", "error", "message", "SQL parse error: " + ex.getMessage()),
                        "application/json");
            }
            total = typeDatabaseService.searchCountBySql(recordClass, query);
        }

        long totalPages = size <= 0 ? 0 : (long) Math.ceil((double) total / (double) size);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("page", page);
        payload.put("size", size);
        payload.put("totalElements", total);
        payload.put("totalPages", totalPages);
        payload.put("content", content);
        return new BindingInvocationResult(200, payload, "application/json");
    }

    private BindingInvocationResult invokeCount(Class<?> recordClass, Map<String, Object> args) {
        String query = stringArg(args, "query", "");
        long total;
        if (query.isBlank()) {
            total = typeDatabaseService.count(recordClass);
        } else {
            try {
                total = typeDatabaseService.searchCountBySql(recordClass, query);
            } catch (SqlParseException ex) {
                return new BindingInvocationResult(400,
                        Map.of("status", "error", "message", "SQL parse error: " + ex.getMessage()),
                        "application/json");
            }
        }

        return new BindingInvocationResult(200, Map.of("totalElements", total), "application/json");
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? fallback : s;
    }

    private Class<?> resolveRecordClass(String bindingId) {
        for (Class<?> recordClass : discoverRecordEntityClasses()) {
            if (bindingIdFor(recordClass).equals(bindingId)) {
                return recordClass;
            }
        }
        throw new IllegalArgumentException("Unknown record binding: " + bindingId);
    }

    private static String resolveId(Map<String, Object> args) {
        String id = stringArg(args, "id", "");
        if (id.isBlank()) {
            id = stringArg(args, "uuid", "");
        }
        if (id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return id;
    }

    private static void requireDefaultProfile(String profile) {
        String resolved = profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile.trim();
        if (!DEFAULT_PROFILE.equalsIgnoreCase(resolved)) {
            throw new IllegalArgumentException("Record bindings only support profile: default");
        }
    }

    private List<Class<?>> discoverRecordEntityClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(DatabaseEntity.class));

        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("sh.vork")) {
            String className = candidate.getBeanClassName();
            if (className == null || className.isBlank()) {
                continue;
            }
            try {
                Class<?> clazz = loadClass(className);
                if (clazz == null) {
                    continue;
                }
                if (!clazz.isRecord()) {
                    continue;
                }
                if (!DatabaseEntity.class.isAssignableFrom(clazz)) {
                    continue;
                }
                if (Modifier.isAbstract(clazz.getModifiers())) {
                    continue;
                }
                if (!isBindingExposed(clazz)) {
                    continue;
                }
                result.add(clazz);
            } catch (ClassNotFoundException ignored) {
                // Skip non-loadable candidates in this runtime profile.
            }
        }

        // Also include runtime-compiled types that may be outside the sh.vork package.
        try (var stream = javaTypeRepository.list(0, Integer.MAX_VALUE)) {
            stream.forEach(javaType -> {
                if (javaType == null || javaType.uuid() == null || javaType.uuid().isBlank()) {
                    return;
                }
                try {
                    Class<?> clazz = loadClass(javaType.uuid());
                    if (clazz == null || !clazz.isRecord() || !DatabaseEntity.class.isAssignableFrom(clazz)) {
                        return;
                    }
                    if (!isBindingExposed(clazz)) {
                        return;
                    }
                    result.add(clazz);
                } catch (ClassNotFoundException ignored) {
                    // Ignore entries whose class cannot be loaded in this runtime.
                }
            });
        }

        List<Class<?>> sorted = new ArrayList<>(result);
        sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return sorted;
    }

    private Class<?> loadClass(String fqn) throws ClassNotFoundException {
        try {
            Class<?> loaded = classLoader.loadClass(fqn);
            if (loaded != null) {
                return loaded;
            }
            return Class.forName(fqn);
        } catch (ClassNotFoundException ex) {
            return Class.forName(fqn);
        }
    }

    private static String bindingIdFor(Class<?> recordClass) {
        return PROVIDER_ID + "." + camelToSnake(recordClass.getSimpleName());
    }

    private static boolean isBindingExposed(Class<?> candidate) {
        GenerateBinding direct = candidate.getAnnotation(GenerateBinding.class);
        if (direct != null) {
            return direct.value();
        }

        for (Class<?> iface : candidate.getInterfaces()) {
            if (!DatabaseEntity.class.isAssignableFrom(iface)) {
                continue;
            }
            GenerateBinding onInterface = iface.getAnnotation(GenerateBinding.class);
            if (onInterface != null) {
                return onInterface.value();
            }
        }

        return false;
    }

    private static String camelToSnake(String value) {
        if (value == null || value.isBlank()) {
            return "record";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static Map<String, Object> idInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of("type", "string")),
                "required", List.of("id"));
    }

    private static Map<String, Object> searchInputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("page", Map.of("type", "integer"));
        props.put("size", Map.of("type", "integer"));
        props.put("sort", Map.of("type", "string"));
        props.put("direction", Map.of("type", "string", "enum", List.of("ASC", "DESC")));
        props.put("query", Map.of("type", "string"));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("page", "size"));
    }

    private static Map<String, Object> countInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of());
    }

    private static Map<String, Object> countOutputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("totalElements", Map.of("type", "integer")),
                "required", List.of("totalElements"));
    }

    private Map<String, Object> searchOutputSchema(Class<?> recordClass) {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page", Map.of("type", "integer"),
                        "size", Map.of("type", "integer"),
                        "totalElements", Map.of("type", "integer"),
                        "totalPages", Map.of("type", "integer"),
                        "content", Map.of(
                                "type", "array",
                                "items", buildRecordSchema(recordClass))),
                "required", List.of("page", "size", "totalElements", "totalPages", "content"));
    }

    private Map<String, Object> buildRecordSchema(Class<?> recordClass) {
        RecordComponent[] components = recordClass.getRecordComponents();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (RecordComponent component : components) {
            properties.put(component.getName(), schemaFor(component.getType(), component.getGenericType()));
            required.add(component.getName());
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", recordClass.getSimpleName());
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> schemaFor(Class<?> type, Type genericType) {
        if (type == String.class || type == Character.class || type == char.class) {
            return Map.of("type", "string");
        }
        if (type == boolean.class || type == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return Map.of("type", "integer");
        }
        if (type == float.class || type == Float.class
                || type == double.class || type == Double.class
                || type == java.math.BigDecimal.class) {
            return Map.of("type", "number");
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            List<String> values = new ArrayList<>();
            if (constants != null) {
                for (Object c : constants) {
                    values.add(String.valueOf(c));
                }
            }
            return Map.of("type", "string", "enum", values);
        }
        if (type.isRecord()) {
            return buildRecordSchema(type);
        }
        if (List.class.isAssignableFrom(type)) {
            Map<String, Object> itemSchema = Map.of("type", "string");
            if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
                Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c) {
                    itemSchema = schemaFor(c, c);
                }
            }
            return Map.of("type", "array", "items", itemSchema);
        }
        if (Map.class.isAssignableFrom(type)) {
            return Map.of("type", "object");
        }
        return Map.of("type", "string");
    }
}
