package sh.vork.skill;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the Skills management page and REST API.
 */
@Controller
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillService skillService;
    private final SkillCategoryService categoryService;

    public SkillController(SkillService skillService,
                           SkillCategoryService categoryService) {
        this.skillService = skillService;
        this.categoryService = categoryService;
    }

    // -- Page ----------------------------------------------------------------

    @GetMapping("/skills")
    public String skillsPage(Model model) {
        log.debug("ENTER skillsPage");
        model.addAttribute("skills", skillService.list());
        model.addAttribute("groups", skillService.listGroups());
        return "skills";
    }

    // -- Skills REST ---------------------------------------------------------

    @GetMapping("/api/skills")
    @ResponseBody
    public ResponseEntity<?> listSkills(@RequestParam(name = "includePrivate", defaultValue = "false") boolean includePrivate) {
        boolean allowPrivate = includePrivate && canViewPrivateSkills();
        return ResponseEntity.ok(skillService.listVisible(allowPrivate));
    }

    @GetMapping("/api/skills/{uuid}")
    @ResponseBody
    public ResponseEntity<?> getSkill(@PathVariable String uuid) {
        Skill skill = skillService.get(uuid);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(skill);
    }

    @PostMapping("/api/skills")
    @ResponseBody
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> createSkill(@RequestBody SkillService.SkillRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
        }
        if (req.groupUuid() == null || req.groupUuid().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupUuid is required."));
        }
        try {
            Skill created = skillService.create(req);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/api/skills/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> updateSkill(@PathVariable String uuid,
                                         @RequestBody SkillService.SkillRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
        }
        if (req.groupUuid() == null || req.groupUuid().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupUuid is required."));
        }
        try {
            Skill updated = skillService.update(uuid, req);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/skills/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> deleteSkill(@PathVariable String uuid) {
        if (skillService.get(uuid) == null) {
            return ResponseEntity.notFound().build();
        }
        skillService.delete(uuid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // -- Group REST ----------------------------------------------------------

    @GetMapping("/api/skill-groups")
    @ResponseBody
    public ResponseEntity<?> listGroups() {
        List<SkillGroupView> groups = skillService.listGroups().stream()
                .map(group -> new SkillGroupView(group, skillService.skillsForGroup(group.uuid())))
                .toList();
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/api/skill-groups/{uuid}")
    @ResponseBody
    public ResponseEntity<?> getGroup(@PathVariable String uuid) {
        SkillGroup group = skillService.getGroup(uuid);
        if (group == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new SkillGroupView(group, skillService.skillsForGroup(group.uuid())));
    }

    @PostMapping("/api/skill-groups")
    @ResponseBody
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> createGroup(@RequestBody SkillService.SkillGroupRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Group name is required."));
        }
        String categoryError = validateCategory(req.category());
        if (categoryError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", categoryError));
        }
        SkillGroup created = skillService.createGroup(req);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/api/skill-groups/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> updateGroup(@PathVariable String uuid,
                                         @RequestBody SkillService.SkillGroupRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Group name is required."));
        }
        String categoryError = validateCategory(req.category());
        if (categoryError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", categoryError));
        }
        SkillGroup updated = skillService.updateGroup(uuid, req);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/api/skill-groups/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> deleteGroup(@PathVariable String uuid) {
        SkillService.GroupDeleteResult result = skillService.deleteGroup(uuid);
        if (!result.ok()) {
            return ResponseEntity.badRequest().body(Map.of("error", result.message()));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // -- Categories ----------------------------------------------------------

    @GetMapping("/api/skills/categories")
    @ResponseBody
    public ResponseEntity<?> listCategories() {
        return ResponseEntity.ok(categoryService.getCategories());
    }

    private String validateCategory(String category) {
        if (category == null || category.isBlank()) {
            return "Category is required and must be selected from supported categories.";
        }
        List<String> supported = categoryService.getCategories();
        boolean match = supported.stream().anyMatch(c -> c.equalsIgnoreCase(category.trim()));
        if (!match) {
            return "Unsupported category. Choose one of the supported categories.";
        }
        return null;
    }

    // -- Export / Import -----------------------------------------------------

    @GetMapping("/api/skill-groups/{uuid}/export")
    @ResponseBody
    public ResponseEntity<?> exportGroup(@PathVariable String uuid) {
        SkillService.SkillGroupExportPackage pkg = skillService.exportGroup(uuid);
        if (pkg == null) {
            return ResponseEntity.notFound().build();
        }

        String safeName = pkg.group().name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "skill-group-" + safeName + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pkg);
    }

    @PostMapping("/api/skill-groups/import")
    @ResponseBody
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> importGroup(@RequestBody SkillService.SkillGroupExportPackage pkg) {
        SkillService.SkillGroupImportResult result = skillService.importGroup(pkg);
        if ("error".equals(result.status()) || "missing_dependencies".equals(result.status())) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<?> handleMalformedImportJson(HttpMessageNotReadableException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage();

        log.warn("Skill import JSON parse failure: {}", detail, ex);

        return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Invalid JSON payload for skill-group import.",
                "detail", detail
        ));
    }

    public record SkillGroupView(SkillGroup group, List<Skill> skills) {}

    private static boolean canViewPrivateSkills() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "SKILLS_WRITE".equals(a.getAuthority()));
    }
}
