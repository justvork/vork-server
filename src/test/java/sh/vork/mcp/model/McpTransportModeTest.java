package sh.vork.mcp.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpTransportModeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesLegacyHttpJsonValue() throws Exception {
        McpTransportMode mode = objectMapper.readValue("\"HTTP_JSON\"", McpTransportMode.class);
        assertEquals(McpTransportMode.STREAMABLE_HTTP, mode);
    }

    @Test
    void deserializesLegacyHttpStreamValue() throws Exception {
        McpTransportMode mode = objectMapper.readValue("\"HTTP_STREAM\"", McpTransportMode.class);
        assertEquals(McpTransportMode.STREAMABLE_HTTP, mode);
    }

    @Test
    void deserializesCurrentValues() throws Exception {
        assertEquals(McpTransportMode.STREAMABLE_HTTP,
                objectMapper.readValue("\"STREAMABLE_HTTP\"", McpTransportMode.class));
        assertEquals(McpTransportMode.SSE,
                objectMapper.readValue("\"SSE\"", McpTransportMode.class));
    }

    @Test
    void rejectsUnknownValues() {
        assertThrows(ValueInstantiationException.class,
                () -> objectMapper.readValue("\"WS\"", McpTransportMode.class));
    }
}
