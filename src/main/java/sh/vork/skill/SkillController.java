package sh.vork.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Serves the Skills management page and REST API.
 */
@Controller
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillService         skillService;
    private final SkillCategoryService categoryService;

    public SkillController(SkillService skillService,
                           SkillCategoryService categoryService) {
        this.skillService    = skillService;
        this.categoryService = categoryService;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/skills")
    public String skillsPage(Model model) {
        log.debug("ENTER skillsPage");
        model.addAttribute("skills", skillService.list());
        return "skills";
    }

    // ── REST: list ────────────────────────────────────────────────────────────

    @GetMapping("/api/skills")
    @ResponseBody
    public ResponseEntity<?> listSkills() {
        log.debug("ENTER listSkills");
        return ResponseEntity.ok(skillService.list());
    }

    // ── REST: categories ──────────────────────────────────────────────────────

    @GetMapping("/api/skills/categories")
    @ResponseBody
    public ResponseEntity<?> listCategories() {
        log.debug("ENTER listCategories");
        return ResponseEntity.ok(categoryService.getCategories());
    }

    // ── REST: get ─────────────────────────────────────────────────────────────

    @GetMapping("/api/skills/{uuid}")
    @ResponseBody
    public ResponseEntity<?> getSkill(@PathVariable String uuid) {
        log.debug("ENTER getSkill: [uuid={}]", uuid);
        Skill skill = skillService.get(uuid);
        if (skill == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(skill);
    }

    // ── REST: create ──────────────────────────────────────────────────────────

    @PostMapping("/api/skills")
    @ResponseBody
    public ResponseEntity<?> createSkill(@RequestBody SkillService.SkillRequest req) {
        log.debug("ENTER createSkill: [name={}]", req.name());
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
        }
        Skill skill = skillService.create(req);
        return ResponseEntity.ok(skill);
    }

    // ── REST: update ──────────────────────────────────────────────────────────

    @PutMapping("/api/skills/{uuid}")
    @ResponseBody
    public ResponseEntity<?> updateSkill(@PathVariable String uuid,
                                         @RequestBody SkillService.SkillRequest req) {
        log.debug("ENTER updateSkill: [uuid={}]", uuid);
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
        }
        Skill updated = skillService.update(uuid, req);
        if (updated == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(updated);
    }

    // ── REST: delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/api/skills/{uuid}")
    @ResponseBody
    public ResponseEntity<?> deleteSkill(@PathVariable String uuid) {
        log.debug("ENTER deleteSkill: [uuid={}]", uuid);
        if (skillService.get(uuid) == null) return ResponseEntity.notFound().build();
        skillService.delete(uuid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── REST: export ──────────────────────────────────────────────────────────

    @GetMapping("/api/skills/{uuid}/export")
    @ResponseBody
    public ResponseEntity<?> exportSkill(@PathVariable String uuid) {
        log.debug("ENTER exportSkill: [uuid={}]", uuid);
        SkillService.SkillExportPackage pkg = skillService.export(uuid);
        if (pkg == null) return ResponseEntity.notFound().build();
        String safeName = pkg.skill().name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename  = "skill-" + safeName + ".json";
        log.debug("EXIT exportSkill: [uuid={}, filename={}]", uuid, filename);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pkg);
    }

    // ── REST: import ──────────────────────────────────────────────────────────

    @PostMapping("/api/skills/import")
    @ResponseBody
    public ResponseEntity<?> importSkill(@RequestBody SkillService.SkillExportPackage pkg) {
        log.debug("ENTER importSkill");
        SkillService.SkillImportResult result = skillService.importSkill(pkg);
        log.debug("EXIT importSkill: [status={}, uuid={}]", result.status(), result.uuid());
        if ("error".equals(result.status())) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
