package sh.vork.surface.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.mockito.ArgumentMatchers;
import org.springframework.mock.web.MockMultipartFile;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.ai.service.ChatService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillVisibility;
import sh.vork.surface.ArtifactStatus;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceReflectionContractService;
import sh.vork.surface.service.SurfaceService;
import sh.vork.surface.service.SurfaceSkillExecutionService;
import sh.vork.util.ZipArchiveUtil;

/**
 * Unit tests for {@link SurfaceController}.
 */
class SurfaceControllerTest {

    @SuppressWarnings("unchecked")
    private static SurfaceController createController(SurfaceService surfaceService,
                                                      SessionFileSystem sessionFileSystem,
                                                      SurfaceReflectionContractService contractService,
                                                      ReflectionService reflectionService,
                                                      ChatService chatService,
                                                      SurfaceSkillExecutionService executionService) {
        DatabaseRepository<Surface> surfaceRepository = mock(DatabaseRepository.class);
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        return new SurfaceController(
                surfaceService,
                surfaceRepository,
                sessionRepository,
                sessionFileSystem,
                contractService,
                reflectionService,
                chatService,
                executionService,
                new ObjectMapper());
    }

    @Test
    void updateSurface_returnsBadRequest_whenServiceRejectsSkillAssignments() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "vork-surface1-SNAPSHOT",
            "surface1",
            "My Surface",
            "desc",
            "session-1",
            "",
            List.of(),
            List.of(),
            List.of(),
            "vork",
            "surface1",
            "SNAPSHOT",
            sh.vork.surface.ArtifactStatus.SNAPSHOT,
            1L,
            1L));

        when(surfaceService.update(
            ArgumentMatchers.eq("vork-surface1-SNAPSHOT"),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("Skill output schema required"));

        ResponseEntity<?> response = controller.updateSurface(
                "surface-1",
                new SurfaceController.UpdateSurfaceRequest(
                        "My Surface",
                        "desc",
                        List.of("skill-1"),
                        List.of(),
                        List.of()));

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("Skill output schema required", body.get("error"));
    }

    @Test
    void deleteSurface_allowsSubmittedSurface() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Surface existing = new Surface(
            "vork-surface1-1.0",
            "surface1",
            "My Surface",
            "desc",
            "session-1",
            "",
            List.of(),
            List.of(),
            List.of(),
            "vork",
            "surface1",
            "1.0",
            ArtifactStatus.SUBMITTED,
            1L,
            1L);

        when(surfaceService.get(existing.uuid())).thenReturn(existing);
        when(surfaceService.delete(existing.uuid())).thenReturn(true);

        ResponseEntity<?> response = controller.deleteSurface(existing.uuid());

        assertEquals(200, response.getStatusCode().value());
        verify(surfaceService).delete(existing.uuid());
    }

    @Test
    void deleteSurface_rejectsStagedSurface() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Surface existing = new Surface(
            "vork-surface1-1.1",
            "surface1",
            "My Surface",
            "desc",
            "session-1",
            "",
            List.of(),
            List.of(),
            List.of(),
            "vork",
            "surface1",
            "1.1",
            ArtifactStatus.STAGED,
            1L,
            1L);

        when(surfaceService.get(existing.uuid())).thenReturn(existing);

        ResponseEntity<?> response = controller.deleteSurface(existing.uuid());

        assertEquals(403, response.getStatusCode().value());
        verify(surfaceService, never()).delete(existing.uuid());
    }

    @Test
    void getSurfaceSession_returnsMessagesInResponse() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        List<AiChatMessage> messages = List.of(
                new AiChatMessage("msg-1", "USER", "Hello", 1L, null),
                new AiChatMessage("msg-2", "ASSISTANT", "Hi there", 2L, null));

        AiSession session = sessionWithMessages(messages);

        Principal principal = () -> "admin";
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);

        ResponseEntity<?> response = controller.getSurfaceSession("surface-1", principal);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("ok", body.get("status"));
        assertEquals("session-1", body.get("sessionUuid"));
        assertEquals("agent-1", body.get("activeAgentTemplateId"));
        assertEquals("Test Surface Session", body.get("name"));
        assertNotNull(body.get("messages"));
        assertEquals(messages, body.get("messages"));
    }

    @Test
    void previewSurfaceFile_returnsSessionFileWithMimeType() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        AiSession session = sessionWithMessages(List.of());
        Principal principal = () -> "admin";

        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "index.html"))
                .thenReturn(new ByteArrayInputStream("<html>Hello</html>".getBytes()));

        ResponseEntity<?> response = controller.previewSurfaceFile("surface-1", "index.html", principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("text/html", response.getHeaders().getContentType().toString());
        byte[] body = assertInstanceOf(byte[].class, response.getBody());
        String html = new String(body);
        assertTrue(html.contains("<html>Hello"));
        assertTrue(html.contains("/surface/runtime/v1/reflections.js"));
        assertTrue(html.contains("/surface/runtime/v1/skills.js"));
        assertTrue(html.contains("/js/surface-preview-console.js"));
        verify(sessionFileSystem).read(FileArea.SESSION, "session-1", "index.html");
    }

    @Test
    void previewSurfaceFile_doesNotInjectRuntimeForNonHtmlAssets() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        AiSession session = sessionWithMessages(List.of());
        Principal principal = () -> "admin";

        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "app.js"))
                .thenReturn(new ByteArrayInputStream("console.log('ok');".getBytes()));

        ResponseEntity<?> response = controller.previewSurfaceFile("surface-1", "app.js", principal);

        assertEquals(200, response.getStatusCode().value());
        byte[] body = assertInstanceOf(byte[].class, response.getBody());
        String js = new String(body);
        assertEquals("console.log('ok');", js);
        assertTrue(!js.contains("surface-preview-console.js"));
    }

    @Test
    void previewSurfaceFile_doesNotDuplicateRuntimeInjectionWhenAlreadyPresent() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        AiSession session = sessionWithMessages(List.of());
        Principal principal = () -> "admin";

        String htmlWithRuntime = """
<html><body>
<h1>Preview</h1>
<script src="/surface/runtime/v1/reflections.js"></script>
<script src="/surface/runtime/v1/skills.js"></script>
<script src="/js/surface-preview-console.js"></script>
</body></html>
""";

        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "index.html"))
                .thenReturn(new ByteArrayInputStream(htmlWithRuntime.getBytes()));

        ResponseEntity<?> response = controller.previewSurfaceFile("surface-1", "index.html", principal);

        assertEquals(200, response.getStatusCode().value());
        byte[] body = assertInstanceOf(byte[].class, response.getBody());
        String html = new String(body);
        assertEquals(1, countOccurrences(html, "/surface/runtime/v1/reflections.js"));
        assertEquals(1, countOccurrences(html, "/surface/runtime/v1/skills.js"));
        assertEquals(1, countOccurrences(html, "/js/surface-preview-console.js"));
    }

    @Test
    void previewSurfaceFile_returnsNotFoundWhenFileMissing() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        AiSession session = sessionWithMessages(List.of());
        Principal principal = () -> "admin";

        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "missing.html"))
                .thenThrow(new java.io.FileNotFoundException("missing"));

        ResponseEntity<?> response = controller.previewSurfaceFile("surface-1", "missing.html", principal);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("Preview file not found", response.getBody());
    }

        @Test
        void exportSurface_writesOnlySurfaceJson() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
            SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Surface surface = new Surface(
            "surface-1",
            "surfaceone",
            "Surface One",
            "desc",
            "editor-session-1",
            "",
            List.of(),
            List.of(),
            List.of(),
            1L,
            1L);

        when(surfaceService.get("surface-1")).thenReturn(surface);
        when(sessionFileSystem.list(FileArea.SESSION, "editor-session-1", ""))
            .thenReturn(List.of(new sh.vork.filesystem.FileNode("index.html", "index.html", false, 12L, 1L)));
        when(sessionFileSystem.read(FileArea.SESSION, "editor-session-1", "index.html"))
            .thenReturn(new ByteArrayInputStream("<html></html>".getBytes()));

        ResponseEntity<?> response = controller.exportSurface("surface-1");

        assertEquals(200, response.getStatusCode().value());
        byte[] zipBytes = assertInstanceOf(byte[].class, response.getBody());
        Map<String, byte[]> entries = ZipArchiveUtil.read(zipBytes);
        assertTrue(entries.containsKey("surface.json"));
        assertTrue(entries.containsKey("assets/index.html"));
        assertTrue(!entries.containsKey("definition.json"));
        String surfaceJson = new String(entries.get("surface.json"));
        assertTrue(!surfaceJson.contains("\"sessionUuid\""));
        assertTrue(!surfaceJson.contains("\"executionSessionUuid\""));
        verify(sessionFileSystem).list(FileArea.SESSION, "editor-session-1", "");
        verify(sessionFileSystem).read(FileArea.SESSION, "editor-session-1", "index.html");
    }

        @Test
    void importSurface_ignoresArchivedSessionFiles() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        DatabaseRepository<Surface> surfaceRepository = mock(DatabaseRepository.class);
        DatabaseRepository<AiSession> sessionRepository = mock(DatabaseRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        SurfaceController controller = new SurfaceController(
            surfaceService,
            surfaceRepository,
            sessionRepository,
            sessionFileSystem,
            contractService,
            reflectionService,
            chatService,
            executionService,
            objectMapper);

        SurfaceController.SurfaceArtifact incoming = new SurfaceController.SurfaceArtifact(
            "vork-importedsurface-SNAPSHOT",
            "Imported Surface",
            "desc",
            List.of(),
            List.of(),
            List.of(),
            "vork",
            "importedsurface",
            "SNAPSHOT",
            sh.vork.surface.ArtifactStatus.SNAPSHOT);
        SurfaceController.SurfaceExportPackage pkg = new SurfaceController.SurfaceExportPackage("1.0", incoming);

        Map<String, byte[]> zipEntries = Map.of(
            "surface.json", objectMapper.writeValueAsBytes(pkg),
            "assets/index.html", "<html>imported</html>".getBytes());
        byte[] archive = ZipArchiveUtil.write(zipEntries);
        MockMultipartFile file = new MockMultipartFile("file", "surface.zip", "application/zip", archive);

        Surface created = new Surface(
            "created-surface-1",
            "imported-surface",
            "Imported Surface",
            "desc",
            "target-editor-session",
            "",
            List.of(),
            List.of(),
            List.of(),
            "vork",
            "importedsurface",
            "SNAPSHOT",
            sh.vork.surface.ArtifactStatus.SNAPSHOT,
            2L,
            2L);
        when(surfaceService.create("Imported Surface", "desc", "admin", "vork", "importedsurface")).thenReturn(created);
        when(surfaceService.update(
            ArgumentMatchers.eq("created-surface-1"),
            ArgumentMatchers.eq("Imported Surface"),
            ArgumentMatchers.eq("desc"),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any()))
            .thenReturn(created);
        when(surfaceService.ensureSession("created-surface-1", "admin")).thenReturn(new AiSession(
            "editor-session-local",
            "GEMINI",
            SessionOriginMode.WEB,
            "admin",
            "Imported Session",
            1L,
            0,
            List.of(),
            null,
            AiSessionStatus.RUNNING,
            null,
            null,
            List.of(),
            List.of(),
            List.of()));

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.importSurface(file, principal);

        assertEquals(200, response.getStatusCode().value());
        verify(sessionFileSystem).write(
            ArgumentMatchers.eq(FileArea.SESSION),
            ArgumentMatchers.eq("editor-session-local"),
            ArgumentMatchers.eq("index.html"),
            ArgumentMatchers.any(InputStream.class),
            ArgumentMatchers.anyLong());
        }

        @Test
        void getSurfaceReflectionContracts_returnsContractsForSurface() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);

        SurfaceReflectionContractService.SurfaceReflectionContractsResponse payload =
            new SurfaceReflectionContractService.SurfaceReflectionContractsResponse(
                "surfaceone", "Surface", List.of());
        when(contractService.contractsForSurface("surface-1", null, null)).thenReturn(payload);

        ResponseEntity<?> response = controller.getSurfaceReflectionContracts("surface-1", null, null, principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(payload, response.getBody());
        }

        @Test
        void invokeSurfaceReflection_executesAttachedBinding() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", "", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
        when(reflectionService.getGroupByToolId("ordersgroup")).thenReturn(new sh.vork.reflection.ReflectionGroup(
            "group-1", "ordersgroup", "Orders Group", "", sh.vork.reflection.ReflectionType.REST,
            "", true, List.of(), List.of(), sh.vork.reflection.ReflectionAuthenticationMode.NONE, "", 1L, 1L, 1L));
        when(reflectionService.getBinding("group-1", "default")).thenReturn(new ReflectionBinding(
            "binding-1", "group-1", "default", "", Map.of(), 1L, 1L, 1L));
        when(reflectionService.getReflectionById("getOrders")).thenReturn(new sh.vork.reflection.Reflection(
            "ref-1", "getOrders", "Get Orders", "", "group-1", List.of(), "GET",
            "https://example.com", Map.of(), Map.of(), "", "application/json", "application/json", "", 1L, 1L, 1L));
        when(reflectionService.executeRestReflection("getOrders", Map.of("limit", 5), "default", "admin"))
            .thenReturn("{\"status\":\"ok\",\"body\":\"[]\"}");

        ResponseEntity<?> response = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("getOrders", "ordersgroup", "default", Map.of("limit", 5)),
            principal);

        assertEquals(200, response.getStatusCode().value());
        String body = assertInstanceOf(String.class, response.getBody());
        assertEquals("[]", body);
        }

    @Test
    void surfaceReflectionRuntimeHelper_returnsJavascriptWithInvokeApi() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        ResponseEntity<String> response = controller.surfaceReflectionRuntimeHelper();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/javascript", response.getHeaders().getContentType().toString());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("window.vork.reflections"));
        assertTrue(body.contains("invoke"));
        assertTrue(body.contains("getContracts"));
    }

    @Test
    void surfaceSkillRuntimeHelper_returnsJavascriptWithInvokeApi() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        ResponseEntity<String> response = controller.surfaceSkillRuntimeHelper();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/javascript", response.getHeaders().getContentType().toString());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("window.vork.skills"));
        assertTrue(body.contains("invoke"));
        assertTrue(body.contains("getContracts"));
    }

    @Test
    void startSurfaceSkillExecution_returnsBadRequest_whenIdsMissing() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.startSurfaceSkillExecution(
                "surface-1",
                new SurfaceController.SurfaceSkillInvokeRequest("", "", Map.of()),
                principal);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void getSurfaceSkillContracts_returnsAttachedSkills() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Skill skill = skillWithJsonOutput("skill-1", "Summarize Orders");
        when(surfaceService.listAttachedSkills("surface-1")).thenReturn(List.of(skill));
        when(surfaceService.publicIdsFor(skill)).thenReturn(new SurfaceService.PublicSkillId("ops", "summarize-orders"));

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.getSurfaceSkillContracts("surface-1", principal);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("surface-1", body.get("surfaceUuid"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) body.get("skills");
        assertEquals(1, skills.size());
        assertEquals("ops", skills.get(0).get("groupId"));
        assertEquals("summarize-orders", skills.get(0).get("skillId"));
        assertEquals("application/json", skills.get(0).get("outputContentType"));
    }

    @Test
    void getSurfaceSkillContracts_returnsForbidden_whenPrincipalMissing() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        ResponseEntity<?> response = controller.getSurfaceSkillContracts("surface-1", null);

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void getSurfaceSkillContracts_returnsNotFound_whenSurfaceMissing() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        when(surfaceService.listAttachedSkills("surface-404"))
                .thenThrow(new IllegalArgumentException("Surface not found: surface-404"));

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.getSurfaceSkillContracts("surface-404", principal);

        assertEquals(404, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void getSurfaceSkillContracts_whenOutputSchemaMalformed_returnsEmptySchemaObject() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Skill malformedSchemaSkill = new Skill(
                "skill-2",
                "Malformed Schema Skill",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                "application/json",
                "{not-valid-json",
                1L,
                1L,
                1L,
                List.of(),
                List.of());

        when(surfaceService.listAttachedSkills("surface-1")).thenReturn(List.of(malformedSchemaSkill));
        when(surfaceService.publicIdsFor(malformedSchemaSkill)).thenReturn(new SurfaceService.PublicSkillId("ops", "malformed-schema-skill"));

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.getSurfaceSkillContracts("surface-1", principal);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skills = (List<Map<String, Object>>) body.get("skills");
        assertEquals(1, skills.size());
        assertInstanceOf(Map.class, skills.get(0).get("outputSchema"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) skills.get(0).get("outputSchema");
        assertTrue(schema.isEmpty());
    }

    @Test
    void startSurfaceSkillExecution_returnsAccepted_whenSkillAndSessionAreValid() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Skill skill = skillWithJsonOutput("skill-1", "Summarize Orders");
        when(surfaceService.resolveAttachedSkillByPublicIds("surface-1", "ops", "summarize-orders")).thenReturn(skill);

        AiSession execSession = new AiSession(
                "exec-session-1",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Execution Session",
                1L,
                0,
                List.of(),
                null,
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
        when(surfaceService.ensureExecutionSession("surface-1", "admin")).thenReturn(execSession);

        SurfaceSkillExecutionService.ExecutionSnapshot started = new SurfaceSkillExecutionService.ExecutionSnapshot(
                "exec-1",
                "surface-1",
                "exec-session-1",
                "skill-1",
                SurfaceSkillExecutionService.ExecutionState.PENDING,
                null,
                null,
                null,
                10L,
                10L,
                null);
        when(executionService.start("surface-1", "exec-session-1", skill, Map.of("topic", "orders"), sh.vork.ai.AiProvider.GEMINI))
                .thenReturn(started);

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.startSurfaceSkillExecution(
                "surface-1",
                new SurfaceController.SurfaceSkillInvokeRequest("ops", "summarize-orders", Map.of("topic", "orders")),
                principal);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("accepted", body.get("status"));
        assertEquals("exec-1", body.get("executionId"));
        assertEquals("PENDING", body.get("state"));
        assertEquals("exec-session-1", body.get("executionSessionUuid"));
        verify(chatService).addSessionSkill("exec-session-1", "skill-1");
    }

        @Test
        void startSurfaceSkillExecution_usesGeminiFallback_whenExecutionSessionProviderInvalid() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Skill skill = skillWithJsonOutput("skill-1", "Summarize Orders");
        when(surfaceService.resolveAttachedSkillByPublicIds("surface-1", "ops", "summarize-orders")).thenReturn(skill);

        AiSession execSession = new AiSession(
            "exec-session-1",
            "INVALID_PROVIDER",
            SessionOriginMode.WEB,
            "admin",
            "Execution Session",
            1L,
            0,
            List.of(),
            null,
            AiSessionStatus.RUNNING,
            null,
            null,
            List.of(),
            List.of(),
            List.of());
        when(surfaceService.ensureExecutionSession("surface-1", "admin")).thenReturn(execSession);

        SurfaceSkillExecutionService.ExecutionSnapshot started = new SurfaceSkillExecutionService.ExecutionSnapshot(
            "exec-1",
            "surface-1",
            "exec-session-1",
            "skill-1",
            SurfaceSkillExecutionService.ExecutionState.PENDING,
            null,
            null,
            null,
            10L,
            10L,
            null);
        when(executionService.start("surface-1", "exec-session-1", skill, Map.of("topic", "orders"), sh.vork.ai.AiProvider.GEMINI))
            .thenReturn(started);

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.startSurfaceSkillExecution(
            "surface-1",
            new SurfaceController.SurfaceSkillInvokeRequest("ops", "summarize-orders", Map.of("topic", "orders")),
            principal);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("accepted", body.get("status"));
        }

    @Test
    void startSurfaceSkillExecution_returnsBadRequest_whenOutputContractIncomplete() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        Skill incomplete = new Skill(
                "skill-3",
                "Incomplete Contract",
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                "none",
                "",
                1L,
                1L,
                1L,
                List.of(),
                List.of());

        when(surfaceService.resolveAttachedSkillByPublicIds("surface-1", "ops", "incomplete-contract")).thenReturn(incomplete);

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.startSurfaceSkillExecution(
                "surface-1",
                new SurfaceController.SurfaceSkillInvokeRequest("ops", "incomplete-contract", Map.of()),
                principal);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
        assertTrue(String.valueOf(body.get("message")).contains("outputContentType=application/json"));
    }

    @Test
    void startSurfaceSkillExecution_returnsBadRequest_whenSkillNotAttached() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        when(surfaceService.resolveAttachedSkillByPublicIds("surface-1", "ops", "missing"))
                .thenThrow(new IllegalArgumentException("No attached skill matches groupId='ops' and skillId='missing'."));

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.startSurfaceSkillExecution(
                "surface-1",
                new SurfaceController.SurfaceSkillInvokeRequest("ops", "missing", Map.of()),
                principal);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void pollSurfaceSkillExecution_returnsCompletedPayload() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        SurfaceSkillExecutionService.ExecutionSnapshot completed = new SurfaceSkillExecutionService.ExecutionSnapshot(
                "exec-1",
                "surface-1",
                "exec-session-1",
                "skill-1",
                SurfaceSkillExecutionService.ExecutionState.COMPLETED,
                "application/json",
                Map.of("total", 2),
                null,
                10L,
                20L,
                20L);
        when(executionService.poll("surface-1", "exec-1", 1500L)).thenReturn(completed);

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.pollSurfaceSkillExecution("surface-1", "exec-1", 1500L, principal);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("ok", body.get("status"));
        assertEquals("COMPLETED", body.get("state"));
        assertEquals("application/json", body.get("outputContentType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertEquals(2, ((Number) result.get("total")).intValue());
    }

    @Test
    void startSurfaceSkillExecution_returnsForbidden_whenPrincipalMissing() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        ResponseEntity<?> response = controller.startSurfaceSkillExecution(
                "surface-1",
                new SurfaceController.SurfaceSkillInvokeRequest("ops", "summarize-orders", Map.of()),
                null);

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void pollSurfaceSkillExecution_returnsForbidden_whenPrincipalMissing() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        ResponseEntity<?> response = controller.pollSurfaceSkillExecution("surface-1", "exec-1", 1000L, null);

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void pollSurfaceSkillExecution_returnsNotFound_whenUnknownExecution() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        ChatService chatService = mock(ChatService.class);
        SurfaceSkillExecutionService executionService = mock(SurfaceSkillExecutionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, chatService, executionService);

        when(executionService.poll("surface-1", "exec-404", 1000L))
                .thenThrow(new IllegalArgumentException("Execution not found: exec-404"));

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.pollSurfaceSkillExecution("surface-1", "exec-404", 1000L, principal);

        assertEquals(404, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void surfaceFlow_fetchContractsThenInvoke_succeeds() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);

        SurfaceReflectionContractService.SurfaceReflectionContractsResponse contracts =
            new SurfaceReflectionContractService.SurfaceReflectionContractsResponse(
                "surfaceone",
                "Surface",
                List.of(new SurfaceReflectionContractService.BindingContract(
                    "ordersgroup",
                    "default",
                    "Orders Group",
                    List.of())));
        when(contractService.contractsForSurface("surface-1", null, null)).thenReturn(contracts);

        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", "", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
        when(reflectionService.getGroupByToolId("ordersgroup")).thenReturn(new sh.vork.reflection.ReflectionGroup(
            "group-1", "ordersgroup", "Orders Group", "", sh.vork.reflection.ReflectionType.REST,
            "", true, List.of(), List.of(), sh.vork.reflection.ReflectionAuthenticationMode.NONE, "", 1L, 1L, 1L));
        when(reflectionService.getBinding("group-1", "default")).thenReturn(new ReflectionBinding(
            "binding-1", "group-1", "default", "", Map.of(), 1L, 1L, 1L));
        when(reflectionService.getReflectionById("getOrders")).thenReturn(new sh.vork.reflection.Reflection(
            "ref-1", "getOrders", "Get Orders", "", "group-1", List.of(), "GET",
            "https://example.com", Map.of(), Map.of(), "", "application/json", "application/json", "", 1L, 1L, 1L));
        when(reflectionService.executeRestReflection("getOrders", Map.of("limit", 25), "default", "admin"))
            .thenReturn("{\"status\":\"ok\",\"body\":[{\"id\":1}]}");

        ResponseEntity<?> contractsResponse = controller.getSurfaceReflectionContracts("surface-1", null, null, principal);
        ResponseEntity<?> invokeResponse = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("getOrders", "ordersgroup", "default", Map.of("limit", 25)),
            principal);

        assertEquals(200, contractsResponse.getStatusCode().value());
        assertEquals(200, invokeResponse.getStatusCode().value());
        String invokeBody = assertInstanceOf(String.class, invokeResponse.getBody());
        assertTrue(invokeBody.contains("\"id\":1"));
        verify(reflectionService).executeRestReflection("getOrders", Map.of("limit", 25), "default", "admin");
    }

    @Test
    void getSurfaceReflectionContracts_forbiddenWhenPrincipalMissing() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        ResponseEntity<?> response = controller.getSurfaceReflectionContracts("surface-1", null, null, null);

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void invokeSurfaceReflection_badRequestWhenBindingGroupToolIdUnknown_returnsAllowedBindingGroupToolIds() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", "", List.of(), List.of("binding-1"), List.of(), 1L, 1L));

        when(reflectionService.getGroupByToolId("openroutedistancecalculator")).thenReturn(null);
        when(reflectionService.getBindingByUuid("binding-1")).thenReturn(new ReflectionBinding(
            "binding-1", "group-1", "default", "", Map.of(), 1L, 1L, 1L));
        when(reflectionService.getGroup("group-1")).thenReturn(new sh.vork.reflection.ReflectionGroup(
            "group-1", "ordersgroup", "Orders Group", "", sh.vork.reflection.ReflectionType.REST,
            "", true, List.of(), List.of(), sh.vork.reflection.ReflectionAuthenticationMode.NONE, "", 1L, 1L, 1L));

        ResponseEntity<?> response = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("locatePostcode", "openroutedistancecalculator", "default", Map.of("postcode", "SW1A 1AA")),
            principal);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
        assertTrue(String.valueOf(body.get("message")).contains("Unknown binding group toolId"));
        assertEquals("openroutedistancecalculator", body.get("providedBindingGroupToolId"));
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) body.get("allowedBindingGroupToolIds");
        assertEquals(List.of("ordersgroup"), allowed);
    }

    @Test
    void invokeSurfaceReflection_badRequestWhenReflectionIdMissing() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        Principal principal = () -> "admin";
        ResponseEntity<?> response = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("", "ordersgroup", "default", Map.of()),
            principal);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
    }

    @Test
    void invokeSurfaceReflection_badRequestWhenBindingNotAttachedToSurface() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", "", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
        when(reflectionService.getGroupByToolId("ordersgroup")).thenReturn(new sh.vork.reflection.ReflectionGroup(
            "group-1", "ordersgroup", "Orders Group", "", sh.vork.reflection.ReflectionType.REST,
            "", true, List.of(), List.of(), sh.vork.reflection.ReflectionAuthenticationMode.NONE, "", 1L, 1L, 1L));
        when(reflectionService.getBinding("group-1", "other")).thenReturn(new ReflectionBinding(
            "binding-other", "group-1", "other", "", Map.of(), 1L, 1L, 1L));

        ResponseEntity<?> response = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("getOrders", "ordersgroup", "other", Map.of()),
            principal);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
        assertEquals("Binding profile is not attached to this surface.", body.get("message"));
    }

    @Test
    void invokeSurfaceReflection_returnsErrorPayloadFromReflectionService() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", "", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
        when(reflectionService.getGroupByToolId("ordersgroup")).thenReturn(new sh.vork.reflection.ReflectionGroup(
            "group-1", "ordersgroup", "Orders Group", "", sh.vork.reflection.ReflectionType.REST,
            "", true, List.of(), List.of(), sh.vork.reflection.ReflectionAuthenticationMode.NONE, "", 1L, 1L, 1L));
        when(reflectionService.getBinding("group-1", "default")).thenReturn(new ReflectionBinding(
            "binding-1", "group-1", "default", "", Map.of(), 1L, 1L, 1L));
        when(reflectionService.executeRestReflection("getOrders", Map.of("limit", 10), "default", "admin"))
            .thenReturn("{\"status\":\"error\",\"message\":\"Remote 500\"}");

        ResponseEntity<?> response = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("getOrders", "ordersgroup", "default", Map.of("limit", 10)),
            principal);

        assertEquals(502, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
        assertEquals("Remote 500", body.get("message"));
    }

    @Test
    void invokeSurfaceReflection_wrapsMalformedNonJsonEnvelopeAsErrorMessage() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", "", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
        when(reflectionService.getGroupByToolId("ordersgroup")).thenReturn(new sh.vork.reflection.ReflectionGroup(
            "group-1", "ordersgroup", "Orders Group", "", sh.vork.reflection.ReflectionType.REST,
            "", true, List.of(), List.of(), sh.vork.reflection.ReflectionAuthenticationMode.NONE, "", 1L, 1L, 1L));
        when(reflectionService.getBinding("group-1", "default")).thenReturn(new ReflectionBinding(
            "binding-1", "group-1", "default", "", Map.of(), 1L, 1L, 1L));
        when(reflectionService.executeRestReflection("getOrders", Map.of("limit", 3), "default", "admin"))
            .thenReturn("upstream gateway timeout");

        ResponseEntity<?> response = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("getOrders", "ordersgroup", "default", Map.of("limit", 3)),
            principal);

        assertEquals(502, response.getStatusCode().value());
        Map<?, ?> body = assertInstanceOf(Map.class, response.getBody());
        assertEquals("error", body.get("status"));
        assertEquals("upstream gateway timeout", body.get("message"));
    }

    @Test
    void invokeSurfaceReflection_passesThroughLargeJsonBodyWithoutTruncation() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = createController(surfaceService, sessionFileSystem, contractService, reflectionService, mock(ChatService.class), mock(SurfaceSkillExecutionService.class));

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.get("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", "", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
        when(reflectionService.getGroupByToolId("ordersgroup")).thenReturn(new sh.vork.reflection.ReflectionGroup(
            "group-1", "ordersgroup", "Orders Group", "", sh.vork.reflection.ReflectionType.REST,
            "", true, List.of(), List.of(), sh.vork.reflection.ReflectionAuthenticationMode.NONE, "", 1L, 1L, 1L));
        when(reflectionService.getBinding("group-1", "default")).thenReturn(new ReflectionBinding(
            "binding-1", "group-1", "default", "", Map.of(), 1L, 1L, 1L));
        when(reflectionService.getReflectionById("getLarge")).thenReturn(new sh.vork.reflection.Reflection(
            "ref-large", "getLarge", "Get Large", "", "group-1", List.of(), "GET",
            "https://example.com", Map.of(), Map.of(), "", "application/json", "application/json", "", 1L, 1L, 1L));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"routes\":[");
        for (int i = 0; i < 1800; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i).append(",\"distance\":554999.9}");
        }
        sb.append("]}");
        String largeJsonBody = sb.toString();
        String escapedBody = largeJsonBody.replace("\\", "\\\\").replace("\"", "\\\"");

        when(reflectionService.executeRestReflection("getLarge", Map.of("postcode", "NG13 9HH"), "default", "admin"))
            .thenReturn("{\"status\":\"ok\",\"statusCode\":200,\"body\":\"" + escapedBody + "\"}");

        ResponseEntity<?> response = controller.invokeSurfaceReflection(
            "surface-1",
            new SurfaceController.SurfaceReflectionInvokeRequest("getLarge", "ordersgroup", "default", Map.of("postcode", "NG13 9HH")),
            principal);

        assertEquals(200, response.getStatusCode().value());
        String body = assertInstanceOf(String.class, response.getBody());
        assertEquals(largeJsonBody, body);
        assertTrue(body.length() > 20_000);
        assertTrue(body.contains("\"routes\""));
    }

    private static AiSession sessionWithMessages(List<AiChatMessage> messages) {
        return new AiSession(
                "session-1",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Test Surface Session",
                1L,
                0,
                messages,
                null,
                AiSessionStatus.RUNNING,
                "agent-1",
                null,
                List.of(),
                List.of(),
                List.of());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    private static Skill skillWithJsonOutput(String uuid, String name) {
        return new Skill(
                uuid,
                name,
                "",
                "group-1",
                SkillVisibility.PUBLIC,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                "application/json",
                "{\"type\":\"object\"}",
                1L,
                1L,
                1L,
                List.of(),
                List.of());
    }
}
