package sh.vork.surface.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionService;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceReflectionContractService;
import sh.vork.surface.service.SurfaceService;

/**
 * Unit tests for {@link SurfaceController}.
 */
class SurfaceControllerTest {

    @Test
    void getSurfaceSession_returnsMessagesInResponse() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
        assertTrue(html.contains("/js/surface-preview-console.js"));
        verify(sessionFileSystem).read(FileArea.SESSION, "session-1", "index.html");
    }

    @Test
    void previewSurfaceFile_doesNotInjectRuntimeForNonHtmlAssets() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        AiSession session = sessionWithMessages(List.of());
        Principal principal = () -> "admin";

        String htmlWithRuntime = """
<html><body>
<h1>Preview</h1>
<script src="/surface/runtime/v1/reflections.js"></script>
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
        assertEquals(1, countOccurrences(html, "/js/surface-preview-console.js"));
    }

    @Test
    void previewSurfaceFile_returnsNotFoundWhenFileMissing() throws Exception {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
        void getSurfaceReflectionContracts_returnsContractsForSurface() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.resolveByUuidOrToolId("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
    void surfaceFlow_fetchContractsThenInvoke_succeeds() {
        SurfaceService surfaceService = mock(SurfaceService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);
        SurfaceReflectionContractService contractService = mock(SurfaceReflectionContractService.class);
        ReflectionService reflectionService = mock(ReflectionService.class);
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);

        SurfaceReflectionContractService.SurfaceReflectionContractsResponse contracts =
            new SurfaceReflectionContractService.SurfaceReflectionContractsResponse(
                "surfaceone",
                "Surface",
                List.of(new SurfaceReflectionContractService.BindingContract(
                    "default",
                    "ordersgroup",
                    "ordersgroup",
                    "Orders Group",
                    List.of())));
        when(contractService.contractsForSurface("surface-1", null, null)).thenReturn(contracts);

        when(surfaceService.resolveByUuidOrToolId("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.resolveByUuidOrToolId("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", List.of(), List.of("binding-1"), List.of(), 1L, 1L));

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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.resolveByUuidOrToolId("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.resolveByUuidOrToolId("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.resolveByUuidOrToolId("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
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
        SurfaceController controller = new SurfaceController(
            surfaceService, sessionFileSystem, contractService, reflectionService, new ObjectMapper());

        Principal principal = () -> "admin";
        AiSession session = sessionWithMessages(List.of());
        when(surfaceService.ensureSession("surface-1", "admin")).thenReturn(session);
        when(surfaceService.resolveByUuidOrToolId("surface-1")).thenReturn(new Surface(
            "surface-1", "surfaceone", "Surface", "", "session-1", List.of(), List.of("binding-1"), List.of(), 1L, 1L));
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
}
