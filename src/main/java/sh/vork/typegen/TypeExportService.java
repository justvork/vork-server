package sh.vork.typegen;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;

/**
 * Discovers and exports Java types that are safe to serialize for transfer/backup.
 */
@Service
public class TypeExportService {

    private static final Logger log = LoggerFactory.getLogger(TypeExportService.class);
    private static final String BUILTIN_SCAN_BASE_PACKAGE = "sh.vork";

    private final JavaTypeClassLoader typeClassLoader;
    private final DatabaseRepository<JavaType> javaTypeRepository;
    private final TypeDatabaseService typeDatabaseService;

    private volatile List<Class<?>> cachedBuiltInExportableTypes;

    public TypeExportService(JavaTypeClassLoader typeClassLoader,
                             DatabaseRepository<JavaType> javaTypeRepository,
                             TypeDatabaseService typeDatabaseService) {
        this.typeClassLoader = typeClassLoader;
        this.javaTypeRepository = javaTypeRepository;
        this.typeDatabaseService = typeDatabaseService;
    }

    public List<ExportableTypeInfo> discoverExportableTypes() {
        log.debug("ENTER discoverExportableTypes");

        LinkedHashMap<String, ExportableTypeInfo> discovered = new LinkedHashMap<>();

        for (Class<?> builtInType : discoverBuiltInExportableTypes()) {
            ExportableType marker = builtInType.getAnnotation(ExportableType.class);
            discovered.put(builtInType.getName(), new ExportableTypeInfo(
                    builtInType.getName(),
                    "BUILT_IN",
                    DatabaseEntity.class.isAssignableFrom(builtInType),
                    marker != null ? marker.description() : "",
                    true));
        }

        try (var stream = javaTypeRepository.list(0, Integer.MAX_VALUE)) {
            stream.forEach(javaType -> {
                String fqn = javaType.uuid();
                boolean entity = false;
                try {
                    Class<?> resolved = resolveTypeClass(fqn);
                    if (resolved != null) {
                        entity = DatabaseEntity.class.isAssignableFrom(resolved);
                    }
                } catch (ClassNotFoundException ignored) {
                    // Keep non-loadable entries discoverable as custom exports.
                }
                discovered.put(fqn, new ExportableTypeInfo(
                        fqn,
                        "CUSTOM",
                        entity,
                        "Runtime-compiled type",
                        true));
            });
        }

        List<ExportableTypeInfo> result = discovered.values().stream()
                .sorted(Comparator.comparing(ExportableTypeInfo::fqn))
                .toList();

        log.debug("EXIT discoverExportableTypes: [count={}]", result.size());
        return result;
    }

    public TypeDataExportPackage exportTypeData(String fqn, String mode, String uuid) {
        ExportMode exportMode = ExportMode.from(mode);
        log.debug("ENTER exportTypeData: [fqn={}, mode={}, uuid={}]", fqn, exportMode, uuid);

        if (fqn == null || fqn.isBlank()) {
            throw new IllegalArgumentException("fqn is required");
        }

        JavaType customType = javaTypeRepository.get(fqn);
        boolean custom = customType != null;

        Class<?> clazz;
        try {
            clazz = resolveTypeClass(fqn);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Type not found: " + fqn);
        }

        ExportableType marker = clazz.getAnnotation(ExportableType.class);
        if (!custom && marker == null) {
            throw new IllegalArgumentException(
                    "Built-in type is not exportable: " + fqn + " (missing @ExportableType)");
        }

        boolean entityType = DatabaseEntity.class.isAssignableFrom(clazz);
        if (!entityType) {
            throw new IllegalArgumentException("Type does not implement DatabaseEntity: " + fqn);
        }

        List<Object> records;
        long count;

        if (exportMode == ExportMode.BY_ID) {
            if (uuid == null || uuid.isBlank()) {
                throw new IllegalArgumentException("uuid is required when mode=BY_ID");
            }
            Object single = typeDatabaseService.get(clazz, uuid);
            records = single == null ? List.of() : List.of(single);
            count = records.size();
        } else {
            count = typeDatabaseService.count(clazz);
            try (var stream = typeDatabaseService.list(clazz, 0, Integer.MAX_VALUE)) {
                records = stream.toList();
            }
        }

        TypeDataExportPackage pkg = new TypeDataExportPackage(
                "1.0",
                fqn,
                custom ? "CUSTOM" : "BUILT_IN",
                true,
                exportMode.name(),
                uuid,
                count,
                records,
                describeType(clazz),
                marker != null ? marker.description() : "Runtime-compiled type");

        log.debug("EXIT exportTypeData: [fqn={}, kind={}, mode={}, count={}]",
                fqn, pkg.kind(), pkg.mode(), pkg.recordCount());
        return pkg;
    }

    public AllTypesDataExportPackage exportAllTypeData() {
        log.debug("ENTER exportAllTypeData");

        List<TypeDataExportPackage> exports = new ArrayList<>();
        for (ExportableTypeInfo info : discoverExportableTypes()) {
            if (!info.entityType()) {
                continue;
            }
            exports.add(exportTypeData(info.fqn(), ExportMode.ALL.name(), null));
        }

        long totalRecords = exports.stream().mapToLong(TypeDataExportPackage::recordCount).sum();
        AllTypesDataExportPackage result = new AllTypesDataExportPackage(
                "1.0",
                exports.size(),
                totalRecords,
                exports);

        log.debug("EXIT exportAllTypeData: [types={}, totalRecords={}]", result.typeCount(), result.totalRecords());
        return result;
    }

    public TypeSourceExportPackage exportTypeSource(String fqn) {
        log.debug("ENTER exportTypeSource: [fqn={}]", fqn);

        if (fqn == null || fqn.isBlank()) {
            throw new IllegalArgumentException("fqn is required");
        }

        JavaType customType = javaTypeRepository.get(fqn);
        if (customType == null) {
            throw new IllegalArgumentException(
                    "Source export is only available for runtime-compiled custom types: " + fqn);
        }

        TypeSourceExportPackage result = new TypeSourceExportPackage(
                "1.0",
                fqn,
                "CUSTOM",
                customType.source());

        log.debug("EXIT exportTypeSource: [fqn={}]", fqn);
        return result;
    }

    private Class<?> resolveTypeClass(String fqn) throws ClassNotFoundException {
        try {
            return typeClassLoader.loadClass(fqn);
        } catch (ClassNotFoundException e) {
            return Class.forName(fqn);
        }
    }

    private List<Class<?>> discoverBuiltInExportableTypes() {
        List<Class<?>> cached = cachedBuiltInExportableTypes;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (cachedBuiltInExportableTypes != null) {
                return cachedBuiltInExportableTypes;
            }

            ArrayList<Class<?>> discovered = new ArrayList<>();
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(ExportableType.class));

            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(BUILTIN_SCAN_BASE_PACKAGE)) {
                String className = beanDefinition.getBeanClassName();
                if (className == null || className.isBlank()) {
                    continue;
                }
                try {
                    Class<?> clazz = Class.forName(className);
                    if (DatabaseEntity.class.isAssignableFrom(clazz)) {
                        discovered.add(clazz);
                    }
                } catch (ClassNotFoundException ignored) {
                    // Ignore classes that are not loadable in this runtime profile.
                }
            }

            discovered.sort(Comparator.comparing(Class::getName));
            cachedBuiltInExportableTypes = List.copyOf(discovered);
            return cachedBuiltInExportableTypes;
        }
    }

    private Map<String, Object> describeType(Class<?> clazz) {
        LinkedHashMap<String, Object> description = new LinkedHashMap<>();
        description.put("fqn", clazz.getName());
        description.put("simpleName", clazz.getSimpleName());
        description.put("record", clazz.isRecord());
        description.put("enum", clazz.isEnum());
        description.put("interface", clazz.isInterface());

        if (clazz.isRecord()) {
            ArrayList<Map<String, String>> components = new ArrayList<>();
            for (RecordComponent component : clazz.getRecordComponents()) {
                components.add(Map.of(
                        "name", component.getName(),
                        "type", component.getType().getName()));
            }
            description.put("components", components);
            return description;
        }

        if (clazz.isEnum()) {
            List<String> values = Arrays.stream(clazz.getEnumConstants())
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
            description.put("enumValues", values);
            return description;
        }

        ArrayList<Map<String, String>> fields = new ArrayList<>();
        Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .forEach(field -> fields.add(Map.of(
                        "name", field.getName(),
                        "type", field.getType().getName())));
        description.put("fields", fields);
        return description;
    }

    public record ExportableTypeInfo(
            String fqn,
            String kind,
            boolean entityType,
            String description,
            boolean exportable
    ) {}

    public record TypeDataExportPackage(
            String exportFormatVersion,
            String fqn,
            String kind,
            boolean entityType,
            String mode,
            String requestedUuid,
            long recordCount,
            List<Object> records,
            Map<String, Object> typeDefinition,
            String description
    ) {}

    public record AllTypesDataExportPackage(
            String exportFormatVersion,
            int typeCount,
            long totalRecords,
            List<TypeDataExportPackage> exports
    ) {}

    public record TypeSourceExportPackage(
            String exportFormatVersion,
            String fqn,
            String kind,
            String source
    ) {}

    public enum ExportMode {
        BY_ID,
        ALL;

        static ExportMode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return BY_ID;
            }
            try {
                return ExportMode.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid mode: " + raw + " (expected BY_ID or ALL)");
            }
        }
    }
}
