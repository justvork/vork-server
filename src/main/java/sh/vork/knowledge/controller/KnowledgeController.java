package sh.vork.knowledge.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sh.vork.knowledge.KnowledgeEntry;
import sh.vork.knowledge.KnowledgeService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * REST API controller for knowledge base CRUD operations.
 * All endpoints require Spring Security authentication.
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeService knowledgeService;

    /**
     * GET /api/knowledge/bases
     * Returns all distinct base categories.
     */
    @GetMapping("/bases")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getBases() {
        Map<String, Object> response = new HashMap<>();
        try {
            var bases = knowledgeService.getAllBases();
            response.put("bases", new ArrayList<>(bases));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * GET /api/knowledge/entries?base=...&page=0&pageSize=20
     * Returns paginated entries for a given base.
     */
    @GetMapping("/entries")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> getEntries(
            @RequestParam String base,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (base == null || base.isBlank()) {
                response.put("status", "error");
                response.put("message", "base parameter is required");
                return ResponseEntity.badRequest().body(response);
            }

            List<Map<String, Object>> entries = new ArrayList<>();
            try (Stream<KnowledgeEntry> stream = knowledgeService.getAll(base.trim(), page, pageSize)) {
                stream.forEach(entry -> entries.add(Map.of(
                        "uuid", entry.uuid(),
                        "base", entry.base(),
                        "content", entry.content(),
                        "createdAt", entry.createdAt(),
                        "updatedAt", entry.updatedAt())));
            }

            long total = knowledgeService.countByBase(base.trim());
            response.put("entries", entries);
            response.put("total", total);
            response.put("page", page);
            response.put("pageSize", pageSize);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * POST /api/knowledge/entries
     * Create a new knowledge entry.
     * Request body: {"base": "...", "content": "..."}
     */
    @PostMapping("/entries")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> createEntry(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String base = body.get("base");
            String content = body.get("content");

            if (base == null || base.isBlank()) {
                response.put("status", "error");
                response.put("message", "base is required");
                return ResponseEntity.badRequest().body(response);
            }
            if (content == null || content.isBlank()) {
                response.put("status", "error");
                response.put("message", "content is required");
                return ResponseEntity.badRequest().body(response);
            }

            KnowledgeEntry entry = knowledgeService.define(base.trim(), content.trim());
            response.put("status", "ok");
            response.put("uuid", entry.uuid());
            response.put("base", entry.base());
            response.put("createdAt", entry.createdAt());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * PUT /api/knowledge/entries/{uuid}
     * Update an existing knowledge entry.
     * Request body: {"base": "...", "content": "..."}
     */
    @PutMapping("/entries/{uuid}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> updateEntry(
            @PathVariable String uuid,
            @RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String base = body.get("base");
            String content = body.get("content");

            if (uuid == null || uuid.isBlank()) {
                response.put("status", "error");
                response.put("message", "uuid is required");
                return ResponseEntity.badRequest().body(response);
            }
            if (base == null || base.isBlank()) {
                response.put("status", "error");
                response.put("message", "base is required");
                return ResponseEntity.badRequest().body(response);
            }
            if (content == null || content.isBlank()) {
                response.put("status", "error");
                response.put("message", "content is required");
                return ResponseEntity.badRequest().body(response);
            }

            KnowledgeEntry updated = knowledgeService.update(uuid.trim(), base.trim(), content.trim());
            if (updated == null) {
                response.put("status", "error");
                response.put("message", "entry not found");
                return ResponseEntity.notFound().build();
            }

            response.put("status", "ok");
            response.put("uuid", updated.uuid());
            response.put("updatedAt", updated.updatedAt());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * DELETE /api/knowledge/entries/{uuid}
     * Delete a knowledge entry.
     */
    @DeleteMapping("/entries/{uuid}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> deleteEntry(@PathVariable String uuid) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (uuid == null || uuid.isBlank()) {
                response.put("status", "error");
                response.put("message", "uuid is required");
                return ResponseEntity.badRequest().body(response);
            }

            knowledgeService.delete(uuid.trim());
            response.put("status", "ok");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
