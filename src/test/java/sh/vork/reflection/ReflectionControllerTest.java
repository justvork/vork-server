package sh.vork.reflection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReflectionControllerTest {

    @Test
    void listTransformationTargetTools_returnsToolDescriptors() throws Exception {
        ReflectionService reflectionService = mock(ReflectionService.class);
        when(reflectionService.listTransformationTargetTools()).thenReturn(List.of(
                new ReflectionService.TransformationTargetToolDescriptor(
                        "writeBase64File",
                        "Write file",
                        List.of("path", "base64Content", "area", "attachToChat"),
                        List.of("path", "base64Content"))));

        ReflectionController controller = new ReflectionController(reflectionService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/reflections/transformation-target-tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("writeBase64File"))
                .andExpect(jsonPath("$[0].inputParameters[3]").value("attachToChat"))
                .andExpect(jsonPath("$[0].requiredParameters[1]").value("base64Content"));
    }
}
