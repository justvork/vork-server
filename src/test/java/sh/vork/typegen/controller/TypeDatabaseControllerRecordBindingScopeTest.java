package sh.vork.typegen.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import jakarta.servlet.http.HttpServletRequest;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionType;
import sh.vork.typegen.FormToObjectConverter;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;
import sh.vork.typegen.TypeRecordBindingScope;
import sh.vork.skill.Skill;

class TypeDatabaseControllerRecordBindingScopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void save_recordScopedRejectsCrossBindingOverwrite() throws Exception {
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<TypeRecordBindingScope> scopeRepository = (DatabaseRepository<TypeRecordBindingScope>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentTemplateRepository = (DatabaseRepository<AgentTemplate>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = (DatabaseRepository<Skill>) mock(DatabaseRepository.class);

        doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Customer");
        when(formConverter.convert(any(), eq(DummyRecord.class))).thenReturn(new DummyRecord("r-1", "Alice"));
        when(scopeRepository.get("sh.vork.generated.Customer::r-1"))
                .thenReturn(null)
                .thenReturn(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::r-1",
                        "sh.vork.generated.Customer",
                        "r-1",
                        "binding-a",
                        "A",
                        1L,
                        1L));

        TypeDatabaseController controller = new TypeDatabaseController(
                typeDatabaseService,
                formConverter,
                classLoader,
                objectMapper,
                javaTypeRepository);
        controller.setTypeRecordBindingScopeRepository(scopeRepository);
        controller.setAiSessionRepository(sessionRepository);
        controller.setAgentTemplateRepository(agentTemplateRepository);
        controller.setSkillRepository(skillRepository);

        AiSession session = new AiSession(
                "s-1",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "admin",
                "Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of("SESSION_REFLECTION_BINDING_UUIDS", "binding-a,binding-b"),
                null,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of());
        when(sessionRepository.get("s-1")).thenReturn(session);
        when(agentTemplateRepository.get("agent-1")).thenReturn(new AgentTemplate(
                "agent-1",
                "Agent",
                "",
                List.of(),
                false,
                List.of(),
                AgentType.INTERACTIVE,
                List.of(),
                List.of(),
                List.of(),
                null,
                "grp",
                "agent",
                "SNAPSHOT",
                sh.vork.ai.agent.ArtifactStatus.SNAPSHOT));

        HttpServletRequest requestA = mock(HttpServletRequest.class);
        when(requestA.getHeader("X-Vork-Reflection-Type")).thenReturn("RECORD");
        when(requestA.getHeader("X-Vork-Reflection-Binding-UUID")).thenReturn("binding-a");
        when(requestA.getHeader("X-Vork-Reflection-Binding-Name")).thenReturn("A");
        when(requestA.getHeader("X-Vork-Session-UUID")).thenReturn("s-1");
        when(requestA.getParameterMap()).thenReturn(Map.of("uuid", new String[] {"r-1"}, "name", new String[] {"Alice"}));

        ResponseEntity<String> first = controller.save("sh.vork.generated.Customer", requestA);
        assertEquals(200, first.getStatusCode().value());
        verify(typeDatabaseService).save(any());
        verify(scopeRepository).save(any(TypeRecordBindingScope.class));

        HttpServletRequest requestB = mock(HttpServletRequest.class);
        when(requestB.getHeader("X-Vork-Reflection-Type")).thenReturn("RECORD");
        when(requestB.getHeader("X-Vork-Reflection-Binding-UUID")).thenReturn("binding-b");
        when(requestB.getHeader("X-Vork-Reflection-Binding-Name")).thenReturn("B");
        when(requestB.getHeader("X-Vork-Session-UUID")).thenReturn("s-1");
        when(requestB.getParameterMap()).thenReturn(Map.of("uuid", new String[] {"r-1"}, "name", new String[] {"Alice"}));

        ResponseEntity<String> second = controller.save("sh.vork.generated.Customer", requestB);
        assertEquals(403, second.getStatusCode().value());
        assertTrue(second.getBody().contains("different binding scope"));
    }

    @Test
    void search_recordScopedFiltersResultsToBindingOwnership() throws Exception {
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<TypeRecordBindingScope> scopeRepository = (DatabaseRepository<TypeRecordBindingScope>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepository = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AgentTemplate> agentTemplateRepository = (DatabaseRepository<AgentTemplate>) mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<Skill> skillRepository = (DatabaseRepository<Skill>) mock(DatabaseRepository.class);

        doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Customer");
        when(typeDatabaseService.searchBySql(eq(DummyRecord.class), eq("name LIKE '%a%'"), eq(0), eq(Integer.MAX_VALUE), eq("uuid"), any()))
                .thenReturn(Stream.of(new DummyRecord("r-1", "Alice"), new DummyRecord("r-2", "Bob")));

        when(scopeRepository.get("sh.vork.generated.Customer::r-1")).thenReturn(new TypeRecordBindingScope(
                "sh.vork.generated.Customer::r-1",
                "sh.vork.generated.Customer",
                "r-1",
                "binding-a",
                "A",
                1L,
                1L));
        when(scopeRepository.get("sh.vork.generated.Customer::r-2")).thenReturn(new TypeRecordBindingScope(
                "sh.vork.generated.Customer::r-2",
                "sh.vork.generated.Customer",
                "r-2",
                "binding-b",
                "B",
                1L,
                1L));

        TypeDatabaseController controller = new TypeDatabaseController(
                typeDatabaseService,
                formConverter,
                classLoader,
                objectMapper,
                javaTypeRepository);
        controller.setTypeRecordBindingScopeRepository(scopeRepository);
        controller.setAiSessionRepository(sessionRepository);
        controller.setAgentTemplateRepository(agentTemplateRepository);
        controller.setSkillRepository(skillRepository);

        AiSession session = new AiSession(
                "s-2",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "admin",
                "Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                Map.of("SESSION_REFLECTION_BINDING_UUIDS", "binding-a"),
                null,
                "agent-2",
                null,
                List.of(),
                List.of(),
                List.of());
        when(sessionRepository.get("s-2")).thenReturn(session);
        when(agentTemplateRepository.get("agent-2")).thenReturn(new AgentTemplate(
                "agent-2",
                "Agent",
                "",
                List.of(),
                false,
                List.of(),
                AgentType.INTERACTIVE,
                List.of(),
                List.of(),
                List.of(),
                null,
                "grp",
                "agent",
                "SNAPSHOT",
                sh.vork.ai.agent.ArtifactStatus.SNAPSHOT));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Vork-Reflection-Type", "RECORD");
        request.addHeader("X-Vork-Reflection-Binding-UUID", "binding-a");
        request.addHeader("X-Vork-Reflection-Binding-Name", "A");
        request.addHeader("X-Vork-Session-UUID", "s-2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            ResponseEntity<String> response = controller.search(
                    "sh.vork.generated.Customer",
                    "name LIKE '%a%'",
                    "SQL",
                    "uuid",
                    "ASC",
                    0,
                    20);

            assertEquals(200, response.getStatusCode().value());
            Map<String, Object> payload = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
            assertEquals(1, payload.get("total"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
            assertEquals(1, results.size());
            assertEquals("r-1", results.get(0).get("uuid"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

        @Test
        void save_recordScopedRejectsWhenBindingNotAttachedToSessionOrAgentOrSkill() throws Exception {
                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<TypeRecordBindingScope> scopeRepository = (DatabaseRepository<TypeRecordBindingScope>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<AiSession> sessionRepository = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<AgentTemplate> agentTemplateRepository = (DatabaseRepository<AgentTemplate>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<Skill> skillRepository = (DatabaseRepository<Skill>) mock(DatabaseRepository.class);

                doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Customer");
                when(formConverter.convert(any(), eq(DummyRecord.class))).thenReturn(new DummyRecord("r-1", "Alice"));

                TypeDatabaseController controller = new TypeDatabaseController(
                                typeDatabaseService,
                                formConverter,
                                classLoader,
                                objectMapper,
                                javaTypeRepository);
                controller.setTypeRecordBindingScopeRepository(scopeRepository);
                controller.setAiSessionRepository(sessionRepository);
                controller.setAgentTemplateRepository(agentTemplateRepository);
                controller.setSkillRepository(skillRepository);

                AiSession session = new AiSession(
                                "s-3",
                                AiProvider.GEMINI.name(),
                                SessionOriginMode.WEB,
                                "admin",
                                "Session",
                                System.currentTimeMillis(),
                                0,
                                List.of(),
                                Map.of("SESSION_REFLECTION_BINDING_UUIDS", "binding-a"),
                                null,
                                "agent-3",
                                null,
                                List.of(),
                                List.of(),
                                List.of());
                when(sessionRepository.get("s-3")).thenReturn(session);
                when(agentTemplateRepository.get("agent-3")).thenReturn(new AgentTemplate(
                                "agent-3",
                                "Agent",
                                "",
                                List.of(),
                                false,
                                List.of(),
                                AgentType.INTERACTIVE,
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                "grp",
                                "agent",
                                "SNAPSHOT",
                                sh.vork.ai.agent.ArtifactStatus.SNAPSHOT));

                HttpServletRequest request = mock(HttpServletRequest.class);
                when(request.getHeader("X-Vork-Reflection-Type")).thenReturn("RECORD");
                when(request.getHeader("X-Vork-Reflection-Binding-UUID")).thenReturn("binding-z");
                when(request.getHeader("X-Vork-Reflection-Binding-Name")).thenReturn("Z");
                when(request.getHeader("X-Vork-Session-UUID")).thenReturn("s-3");
                when(request.getParameterMap()).thenReturn(Map.of("uuid", new String[] {"r-1"}, "name", new String[] {"Alice"}));

                ResponseEntity<String> response = controller.save("sh.vork.generated.Customer", request);

                assertEquals(400, response.getStatusCode().value());
                assertTrue(response.getBody().contains("not attached"));
        }

            @Test
            void search_recordScopedWithoutSessionUuidUsesBindingScopeWhenBindingExists() throws Exception {
                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<TypeRecordBindingScope> scopeRepository = (DatabaseRepository<TypeRecordBindingScope>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ReflectionBinding> reflectionBindingRepository = (DatabaseRepository<ReflectionBinding>) mock(DatabaseRepository.class);

                doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Customer");
                when(typeDatabaseService.searchBySql(eq(DummyRecord.class), eq("name LIKE '%a%'"), eq(0), eq(Integer.MAX_VALUE), eq("uuid"), any()))
                        .thenReturn(Stream.of(new DummyRecord("r-1", "Alice"), new DummyRecord("r-2", "Bob")));

                when(scopeRepository.get("sh.vork.generated.Customer::r-1")).thenReturn(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::r-1",
                        "sh.vork.generated.Customer",
                        "r-1",
                        "binding-a",
                        "A",
                        1L,
                        1L));
                when(scopeRepository.get("sh.vork.generated.Customer::r-2")).thenReturn(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::r-2",
                        "sh.vork.generated.Customer",
                        "r-2",
                        "binding-b",
                        "B",
                        1L,
                        1L));
                when(reflectionBindingRepository.get("binding-a")).thenReturn(new ReflectionBinding(
                        "binding-a",
                        "group-a",
                        "A",
                        "",
                        Map.of(),
                        1L,
                        1L,
                        1L));

                TypeDatabaseController controller = new TypeDatabaseController(
                        typeDatabaseService,
                        formConverter,
                        classLoader,
                        objectMapper,
                        javaTypeRepository);
                controller.setTypeRecordBindingScopeRepository(scopeRepository);
                controller.setReflectionBindingRepository(reflectionBindingRepository);

                MockHttpServletRequest request = new MockHttpServletRequest();
                request.addHeader("X-Vork-Reflection-Type", "RECORD");
                request.addHeader("X-Vork-Reflection-Binding-UUID", "binding-a");
                request.addHeader("X-Vork-Reflection-Binding-Name", "A");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                try {
                    ResponseEntity<String> response = controller.search(
                            "sh.vork.generated.Customer",
                            "name LIKE '%a%'",
                            "SQL",
                            "uuid",
                            "ASC",
                            0,
                            20);

                    assertEquals(200, response.getStatusCode().value());
                    Map<String, Object> payload = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
                    assertEquals(1, payload.get("total"));

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
                    assertEquals(1, results.size());
                    assertEquals("r-1", results.get(0).get("uuid"));
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            }

            @Test
            void search_bindingOwnedByRecordGroupScopesEvenWhenHeaderTypeIsRest() throws Exception {
                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<TypeRecordBindingScope> scopeRepository = (DatabaseRepository<TypeRecordBindingScope>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ReflectionBinding> reflectionBindingRepository = (DatabaseRepository<ReflectionBinding>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<ReflectionGroup> reflectionGroupRepository = (DatabaseRepository<ReflectionGroup>) mock(DatabaseRepository.class);

                doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Customer");
                when(typeDatabaseService.list(eq(DummyRecord.class), eq(0), eq(Integer.MAX_VALUE)))
                        .thenReturn(Stream.of(new DummyRecord("r-1", "Alice"), new DummyRecord("r-2", "Bob")));

                when(scopeRepository.get("sh.vork.generated.Customer::r-1")).thenReturn(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::r-1",
                        "sh.vork.generated.Customer",
                        "r-1",
                        "binding-a",
                        "A",
                        1L,
                        1L));
                when(scopeRepository.get("sh.vork.generated.Customer::r-2")).thenReturn(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::r-2",
                        "sh.vork.generated.Customer",
                        "r-2",
                        "binding-b",
                        "B",
                        1L,
                        1L));

                when(reflectionBindingRepository.get("binding-a")).thenReturn(new ReflectionBinding(
                        "binding-a",
                        "group-record-a",
                        "A",
                        "",
                        Map.of(),
                        1L,
                        1L,
                        1L));
                when(reflectionGroupRepository.get("group-record-a")).thenReturn(new ReflectionGroup(
                        "group-record-a",
                        "tool-record-a",
                        "Record A",
                        "",
                        ReflectionType.RECORD,
                        "",
                        true,
                        List.of(),
                        List.of(),
                        sh.vork.reflection.ReflectionAuthenticationMode.NONE,
                        "",
                        "record",
                        "Customer",
                        "SNAPSHOT",
                        sh.vork.reflection.ArtifactStatus.SNAPSHOT,
                        1L,
                        1L));

                TypeDatabaseController controller = new TypeDatabaseController(
                        typeDatabaseService,
                        formConverter,
                        classLoader,
                        objectMapper,
                        javaTypeRepository);
                controller.setTypeRecordBindingScopeRepository(scopeRepository);
                controller.setReflectionBindingRepository(reflectionBindingRepository);
                controller.setReflectionGroupRepository(reflectionGroupRepository);

                MockHttpServletRequest request = new MockHttpServletRequest();
                request.addHeader("X-Vork-Reflection-Type", "REST");
                request.addHeader("X-Vork-Reflection-Binding-UUID", "binding-a");
                request.addHeader("X-Vork-Reflection-Binding-Name", "A");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                try {
                    ResponseEntity<String> response = controller.list("sh.vork.generated.Customer", 0, 20);

                    assertEquals(200, response.getStatusCode().value());
                    List<Map<String, Object>> rows = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
                    assertEquals(1, rows.size());
                    assertEquals("r-1", rows.getFirst().get("uuid"));
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            }

            @Test
            void list_recordScopedFallsBackToBindingNameWhenScopeUuidDiffers() throws Exception {
                TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
                FormToObjectConverter formConverter = mock(FormToObjectConverter.class);
                JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<JavaType> javaTypeRepository = (DatabaseRepository<JavaType>) mock(DatabaseRepository.class);
                @SuppressWarnings("unchecked")
                DatabaseRepository<TypeRecordBindingScope> scopeRepository = (DatabaseRepository<TypeRecordBindingScope>) mock(DatabaseRepository.class);

                doReturn(DummyRecord.class).when(classLoader).loadClass("sh.vork.generated.Customer");
                when(typeDatabaseService.list(eq(DummyRecord.class), eq(0), eq(Integer.MAX_VALUE)))
                        .thenReturn(Stream.of(new DummyRecord("r-1", "Alice"), new DummyRecord("r-2", "Bob")));

                // Record r-1 was scoped under an older binding UUID but same binding name.
                when(scopeRepository.get("sh.vork.generated.Customer::r-1")).thenReturn(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::r-1",
                        "sh.vork.generated.Customer",
                        "r-1",
                        "binding-a-old",
                        "A",
                        1L,
                        1L));
                when(scopeRepository.get("sh.vork.generated.Customer::r-2")).thenReturn(new TypeRecordBindingScope(
                        "sh.vork.generated.Customer::r-2",
                        "sh.vork.generated.Customer",
                        "r-2",
                        "binding-b",
                        "B",
                        1L,
                        1L));

                TypeDatabaseController controller = new TypeDatabaseController(
                        typeDatabaseService,
                        formConverter,
                        classLoader,
                        objectMapper,
                        javaTypeRepository);
                controller.setTypeRecordBindingScopeRepository(scopeRepository);

                MockHttpServletRequest request = new MockHttpServletRequest();
                request.addHeader("X-Vork-Reflection-Type", "RECORD");
                request.addHeader("X-Vork-Reflection-Binding-UUID", "binding-a-new");
                request.addHeader("X-Vork-Reflection-Binding-Name", "A");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                try {
                    ResponseEntity<String> response = controller.list("sh.vork.generated.Customer", 0, 20);

                    assertEquals(200, response.getStatusCode().value());
                    List<Map<String, Object>> rows = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
                    assertEquals(1, rows.size());
                    assertEquals("r-1", rows.getFirst().get("uuid"));
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            }

    private record DummyRecord(String uuid, String name) {}
}
