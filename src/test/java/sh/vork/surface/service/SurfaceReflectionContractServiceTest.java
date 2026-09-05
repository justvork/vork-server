package sh.vork.surface.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionService;
import sh.vork.reflection.ReflectionType;
import sh.vork.surface.Surface;

class SurfaceReflectionContractServiceTest {

    @Test
    void contractsForSurface_excludesPrivateContractToolsFromAdvertisedReflections() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<Surface> surfaceRepository = mock(DatabaseRepository.class);
        ReflectionService reflectionService = mock(ReflectionService.class);

        SurfaceReflectionContractService service = new SurfaceReflectionContractService(
                surfaceRepository,
                reflectionService,
                new ObjectMapper());

        Surface surface = new Surface(
                "surface-1",
                "surface1",
                "Surface One",
                "",
                "session-1",
                "",
                List.of(),
                List.of("binding-1"),
                List.of(),
                1L,
                1L);

        ReflectionBinding binding = new ReflectionBinding(
                "binding-1",
                "group-1",
                "default",
                "",
                Map.of(),
                1L,
                1L,
                1L);

        ReflectionGroup group = new ReflectionGroup(
                "group-1",
                "weathergroup",
                "Weather Group",
                "",
                ReflectionType.REST,
                "",
                true,
                List.of(),
                List.of(),
                null,
                "",
                List.of(),
                "legacy",
                "reflectiongroup",
                "SNAPSHOT",
                sh.vork.artifact.ArtifactStatus.SNAPSHOT,
                1L,
                1L);

        Reflection publicReflection = new Reflection(
                "r-public",
                "publicTool",
                "Public Tool",
                "",
                group.uuid(),
                List.of(),
                "GET",
                "",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                "",
                1L,
                1L,
                1L);

        Reflection privateReflection = new Reflection(
                "r-private",
                "privateTool",
                "Private Tool",
                "",
                group.uuid(),
                List.of(),
                "GET",
                "",
                Map.of(),
                Map.of(),
                "",
                "application/json",
                "application/json",
                "",
                1L,
                1L,
                1L);

        when(surfaceRepository.get("surface-1")).thenReturn(surface);
        when(reflectionService.getBindingByUuid("binding-1")).thenReturn(binding);
        when(reflectionService.getBindingGroup(binding)).thenReturn(group);
        when(reflectionService.reflectionsForGroup(group.uuid())).thenReturn(List.of(publicReflection, privateReflection));
        when(reflectionService.isReflectionAdvertisedToConsumers(group, "publicTool")).thenReturn(true);
        when(reflectionService.isReflectionAdvertisedToConsumers(group, "privateTool")).thenReturn(false);

        SurfaceReflectionContractService.SurfaceReflectionContractsResponse response =
                service.contractsForSurface("surface-1", null, null);

        assertEquals(1, response.bindings().size());
        assertEquals(1, response.bindings().getFirst().reflections().size());
        assertEquals("publicTool", response.bindings().getFirst().reflections().getFirst().reflectionId());
    }
}
