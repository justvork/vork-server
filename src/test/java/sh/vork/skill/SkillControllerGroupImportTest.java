package sh.vork.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillControllerGroupImportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void importGroup_returnsBadRequest_forMissingDependencies() throws Exception {
        SkillService skillService = mock(SkillService.class);
        SkillCategoryService categoryService = mock(SkillCategoryService.class);

        when(skillService.importGroup(any())).thenReturn(new SkillService.SkillGroupImportResult(
                "missing_dependencies",
                "grp-mail",
                List.of(),
                List.of("skill-send -> skill-connect"),
                "Import blocked"));

        SkillController controller = new SkillController(skillService, categoryService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        SkillGroup group = new SkillGroup("grp-mail", "Mail Skills", "ops", "Productivity", List.of(), 1, 1, 1);
        SkillService.SkillGroupExportPackage pkg = new SkillService.SkillGroupExportPackage("1.0", group, List.of());

        mvc.perform(post("/api/skill-groups/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pkg)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("missing_dependencies"));
    }

    @Test
    void importGroup_returnsOk_forImported() throws Exception {
        SkillService skillService = mock(SkillService.class);
        SkillCategoryService categoryService = mock(SkillCategoryService.class);

        when(skillService.importGroup(any())).thenReturn(new SkillService.SkillGroupImportResult(
                "imported",
                "grp-mail",
                List.of("skill-connect"),
                List.of(),
                null));

        SkillController controller = new SkillController(skillService, categoryService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        SkillGroup group = new SkillGroup("grp-mail", "Mail Skills", "ops", "Productivity", List.of(), 1, 1, 1);
        SkillService.SkillGroupExportPackage pkg = new SkillService.SkillGroupExportPackage("1.0", group, List.of());

        mvc.perform(post("/api/skill-groups/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pkg)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("imported"));
    }

    @Test
    void listGroups_returnsGroupWithSkills_forGroupFirstUi() throws Exception {
        SkillService skillService = mock(SkillService.class);
        SkillCategoryService categoryService = mock(SkillCategoryService.class);

        Skill skill = new Skill(
                "skill-send",
                "Send Mail",
                "Sends an email",
                "grp-mail",
                SkillVisibility.PUBLIC,
                List.of(),
                "Send email",
                List.of(),
                List.of(),
                List.of(),
                1,
                1,
                1,
                List.of());
        SkillGroup group = new SkillGroup("grp-mail", "Mail Skills", "ops", "Productivity", List.of(skill), 1, 1, 1);

        when(skillService.listGroups()).thenReturn(List.of(group));
        when(skillService.skillsForGroup(eq("grp-mail"))).thenReturn(List.of(skill));

        SkillController controller = new SkillController(skillService, categoryService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/skill-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].group.uuid").value("grp-mail"))
                .andExpect(jsonPath("$[0].group.name").value("Mail Skills"))
                .andExpect(jsonPath("$[0].skills[0].uuid").value("skill-send"))
                .andExpect(jsonPath("$[0].skills[0].name").value("Send Mail"));
    }

    @Test
    void skillsPage_populatesGroupsAndSkillsModel_forClientRendering() throws Exception {
        SkillService skillService = mock(SkillService.class);
        SkillCategoryService categoryService = mock(SkillCategoryService.class);

        when(skillService.list()).thenReturn(List.of());
        when(skillService.listGroups()).thenReturn(List.of(
                new SkillGroup("grp-mail", "Mail Skills", "ops", "Productivity", List.of(), 1, 1, 1)
        ));

        SkillController controller = new SkillController(skillService, categoryService);
        Model model = new ExtendedModelMap();
        String viewName = controller.skillsPage(model);

        assertEquals("skills", viewName);
        assertTrue(model.containsAttribute("skills"));
        assertTrue(model.containsAttribute("groups"));
    }
}
