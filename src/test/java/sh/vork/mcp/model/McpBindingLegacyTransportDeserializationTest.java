package sh.vork.mcp.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpBindingLegacyTransportDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void convertValueMapsLegacyHttpStreamToStreamableHttp() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("uuid", "b-1");
        doc.put("transportMode", "HTTP_STREAM");

        McpBinding binding = mapper.convertValue(doc, McpBinding.class);

        assertEquals(McpTransportMode.STREAMABLE_HTTP, binding.transportMode());
    }

    @Test
    void convertValueMapsLegacyHttpJsonToStreamableHttp() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("uuid", "b-2");
        doc.put("transportMode", "HTTP_JSON");

        McpBinding binding = mapper.convertValue(doc, McpBinding.class);

        assertEquals(McpTransportMode.STREAMABLE_HTTP, binding.transportMode());
    }
}
