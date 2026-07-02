package sh.vork.typegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.skill.Skill;

class TypeExportServiceTest {

    @Test
    void discoverExportableTypes_includesAnnotatedBuiltInsAndCustomTypes() {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = mock(DatabaseRepository.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        JavaType custom = new JavaType(
                "sh.vork.generated.OrderRecord",
                "package sh.vork.generated; public record OrderRecord(String uuid) {}",
                Map.of("sh.vork.generated.OrderRecord", "AQID"),
                1L,
                1L);
        when(javaTypeRepository.list(0, Integer.MAX_VALUE)).thenReturn(Stream.of(custom));

        TypeExportService service = new TypeExportService(classLoader, javaTypeRepository, typeDatabaseService);
        List<TypeExportService.ExportableTypeInfo> exported = service.discoverExportableTypes();

        assertTrue(exported.stream().anyMatch(info -> info.fqn().equals(Skill.class.getName())
                && info.kind().equals("BUILT_IN")
                && info.exportable()));
        assertTrue(exported.stream().anyMatch(info -> info.fqn().equals("sh.vork.generated.OrderRecord")
                && info.kind().equals("CUSTOM")
                && info.exportable()));
    }

    @Test
    void exportType_rejectsBuiltInTypeWithoutExportableAnnotation() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = mock(DatabaseRepository.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        when(javaTypeRepository.get("sh.vork.internal.LocalNonExportable")).thenReturn(null);
        doReturn(LocalNonExportable.class).when(classLoader).loadClass("sh.vork.internal.LocalNonExportable");

        TypeExportService service = new TypeExportService(classLoader, javaTypeRepository, typeDatabaseService);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                () -> service.exportTypeData("sh.vork.internal.LocalNonExportable", "BY_ID", "1"));
        assertTrue(ex.getMessage().contains("missing @ExportableType"));
    }

    @Test
        void exportTypeData_customEntityType_allMode_includesAllRecordsAndNoSourceField() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = mock(DatabaseRepository.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        String fqn = "sh.vork.generated.CustomerRecord";
        JavaType custom = new JavaType(
                fqn,
                "package sh.vork.generated; public record CustomerRecord(String uuid, String name) {}",
                Map.of(fqn, "AQID"),
                1L,
                2L);

        when(javaTypeRepository.get(fqn)).thenReturn(custom);
        doReturn(LocalCustomRecord.class).when(classLoader).loadClass(fqn);
        when(typeDatabaseService.count(LocalCustomRecord.class)).thenReturn(1L);
        when(typeDatabaseService.list(LocalCustomRecord.class, 0, Integer.MAX_VALUE))
                .thenReturn(Stream.of(new LocalCustomRecord("id-1", "Alice")));

        TypeExportService service = new TypeExportService(classLoader, javaTypeRepository, typeDatabaseService);
        TypeExportService.TypeDataExportPackage pkg = service.exportTypeData(fqn, "ALL", null);

        assertEquals("CUSTOM", pkg.kind());
        assertTrue(pkg.entityType());
        assertEquals("ALL", pkg.mode());
        assertEquals(1L, pkg.recordCount());
        assertFalse(pkg.records().isEmpty());
    }

    @Test
    void exportTypeData_byIdMode_returnsSingleRecordOnly() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = mock(DatabaseRepository.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        String fqn = "sh.vork.generated.CustomerRecord";
        JavaType custom = new JavaType(
                fqn,
                "package sh.vork.generated; public record CustomerRecord(String uuid, String name) {}",
                Map.of(fqn, "AQID"),
                1L,
                2L);

        when(javaTypeRepository.get(fqn)).thenReturn(custom);
        doReturn(LocalCustomRecord.class).when(classLoader).loadClass(fqn);
        when(typeDatabaseService.get(LocalCustomRecord.class, "id-1"))
                .thenReturn(new LocalCustomRecord("id-1", "Alice"));

        TypeExportService service = new TypeExportService(classLoader, javaTypeRepository, typeDatabaseService);
        TypeExportService.TypeDataExportPackage pkg = service.exportTypeData(fqn, "BY_ID", "id-1");

        assertEquals("BY_ID", pkg.mode());
        assertEquals("id-1", pkg.requestedUuid());
        assertEquals(1L, pkg.recordCount());
        assertEquals(1, pkg.records().size());
    }

    @Test
    void exportTypeSource_customType_returnsSourceSeparately() {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = mock(DatabaseRepository.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        String fqn = "sh.vork.generated.CustomerRecord";
        JavaType custom = new JavaType(
                fqn,
                "package sh.vork.generated; public record CustomerRecord(String uuid, String name) {}",
                Map.of(fqn, "AQID"),
                1L,
                2L);
        when(javaTypeRepository.get(fqn)).thenReturn(custom);

        TypeExportService service = new TypeExportService(classLoader, javaTypeRepository, typeDatabaseService);
        TypeExportService.TypeSourceExportPackage source = service.exportTypeSource(fqn);

        assertEquals(fqn, source.fqn());
        assertEquals("CUSTOM", source.kind());
        assertEquals(custom.source(), source.source());
    }

    private record LocalNonExportable(String uuid) implements DatabaseEntity {}

    private record LocalCustomRecord(String uuid, String name) implements DatabaseEntity {}
}
