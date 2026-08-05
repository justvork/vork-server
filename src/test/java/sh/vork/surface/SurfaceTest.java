package sh.vork.surface;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Surface} record.
 */
class SurfaceTest {

    @Test
    void constructorNormalizesNullValues() {
        Surface surface = new Surface("uuid-1", null, null, null, null, null, null, null, 1L, 2L);

        assertEquals("uuid-1", surface.uuid());
        assertEquals("untitledsurface", surface.toolId());
        assertEquals("Untitled Surface", surface.name());
        assertEquals("", surface.description());
        assertEquals("", surface.sessionUuid());
        assertNotNull(surface.skillUuids());
        assertTrue(surface.skillUuids().isEmpty());
        assertNotNull(surface.reflectionBindingUuids());
        assertTrue(surface.reflectionBindingUuids().isEmpty());
        assertNotNull(surface.jobUuids());
        assertTrue(surface.jobUuids().isEmpty());
    }

    @Test
    void constructorPreservesAssignments() {
        Surface surface = new Surface(
                "uuid-2",
            "dashboard1",
                "Dashboard",
                "A project dashboard",
                "session-1",
                List.of("skill-1"),
                List.of("binding-1"),
                List.of("job-1"),
                100L,
                200L);

            assertEquals("dashboard1", surface.toolId());
        assertEquals("Dashboard", surface.name());
        assertEquals("A project dashboard", surface.description());
        assertEquals("session-1", surface.sessionUuid());
        assertEquals(List.of("skill-1"), surface.skillUuids());
        assertEquals(List.of("binding-1"), surface.reflectionBindingUuids());
        assertEquals(List.of("job-1"), surface.jobUuids());
    }
}
