package sh.vork.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.orm.DatabaseRepository;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.TypeDatabaseService;

class RecordBindingProviderTest {

        private static final String TEST_BINDING_ID = "record.test_bound_entity";

    @Test
        void listBindings_includesAnnotatedEntityBinding() {
        RecordBindingProvider provider = new RecordBindingProvider(
                mock(TypeDatabaseService.class),
                mock(JavaTypeClassLoader.class),
                (DatabaseRepository<JavaType>) mock(DatabaseRepository.class),
                new ObjectMapper());

        List<BindingSummary> bindings = provider.listBindings();

        assertFalse(bindings.isEmpty());
                assertTrue(bindings.stream().anyMatch(b -> TEST_BINDING_ID.equals(b.bindingId())));
        assertTrue(bindings.stream().allMatch(b -> b.profiles().size() == 1 && "default".equalsIgnoreCase(b.profiles().get(0))));
    }

    @Test
        void listOperationContracts_defaultProfile_hasCrudSearchAndCount() {
        RecordBindingProvider provider = new RecordBindingProvider(
                mock(TypeDatabaseService.class),
                mock(JavaTypeClassLoader.class),
                (DatabaseRepository<JavaType>) mock(DatabaseRepository.class),
                new ObjectMapper());

        List<BindingOperationContract> contracts = provider.listOperationContracts(TEST_BINDING_ID, "default");

        assertEquals(6, contracts.size());
        assertTrue(contracts.stream().anyMatch(c -> "create".equals(c.operationId())));
        assertTrue(contracts.stream().anyMatch(c -> "read".equals(c.operationId())));
        assertTrue(contracts.stream().anyMatch(c -> "update".equals(c.operationId())));
        assertTrue(contracts.stream().anyMatch(c -> "delete".equals(c.operationId())));
        assertTrue(contracts.stream().anyMatch(c -> "search".equals(c.operationId())));
        assertTrue(contracts.stream().anyMatch(c -> "count".equals(c.operationId())));

        BindingOperationContract search = contracts.stream()
                .filter(c -> "search".equals(c.operationId()))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchema = (Map<String, Object>) search.inputSchema();
        assertEquals("object", inputSchema.get("type"));
    }

    @Test
    void invoke_nonDefaultProfile_rejected() {
        RecordBindingProvider provider = new RecordBindingProvider(
                mock(TypeDatabaseService.class),
                mock(JavaTypeClassLoader.class),
                (DatabaseRepository<JavaType>) mock(DatabaseRepository.class),
                new ObjectMapper());

        BindingInvocationRequest req = new BindingInvocationRequest(
                TEST_BINDING_ID,
                "custom",
                "search",
                Map.of("page", 0, "size", 10),
                "tester");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> provider.invoke(req));
        assertTrue(ex.getMessage().contains("default"));
    }
}
