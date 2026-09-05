package sh.vork.scheduling.controller;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
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

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.artifact.ArtifactStatus;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillVisibility;
import sh.vork.security.UserManagementService;

/**
 * Page and REST API controller for the Jobs management UI.
 *
 * <p>All REST endpoints are scoped to the authenticated user — only that user's
 * own jobs are visible or modifiable.
 */
@Controller
public class JobsController {

    private static final Logger log = LoggerFactory.getLogger(JobsController.class);
    private static final ObjectMapper EXPORT_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String SNAPSHOT_VERSION = "SNAPSHOT";
    private static final int GROUP_ID_MIN_LEN = 3;
    private static final int GROUP_ID_MAX_LEN = 64;
    private static final int ARTIFACT_ID_MIN_LEN = 3;
    private static final int ARTIFACT_ID_MAX_LEN = 64;
    private static final int VERSION_MAX_LEN = 16;
    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");

    private final AiSchedulerService schedulerService;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final DatabaseRepository<AiSession> sessionRepository;
    private final DatabaseRepository<Skill> skillRepository;
    private final UserManagementService userManagementService;

    public JobsController(AiSchedulerService schedulerService,
                          DatabaseRepository<ScheduledJob> jobRepository,
                          DatabaseRepository<AiSession> sessionRepository,
                          DatabaseRepository<Skill> skillRepository,
                          UserManagementService userManagementService) {
        this.schedulerService = schedulerService;
        this.jobRepository = jobRepository;
        this.sessionRepository = sessionRepository;
        this.skillRepository = skillRepository;
        this.userManagementService = userManagementService;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/jobs")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String jobsPage(Model model, @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER jobsPage: [user={}]", user.getUsername());
        model.addAttribute("jobs", schedulerService.listJobsForUser(user.getUsername()));
        model.addAttribute("invocationTypes", Arrays.stream(InvocationType.values())
            .filter(type -> type != InvocationType.DYNAMIC)
            .toList());
        model.addAttribute("durationTypes", DurationType.values());
        return "jobs";
    }

    // ── REST: list ────────────────────────────────────────────────────────────

    @GetMapping("/api/jobs")
    @ResponseBody
    public List<ScheduledJob> listJobs(@AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER listJobs: [user={}]", user.getUsername());
        return schedulerService.listJobsForUser(user.getUsername());
    }

    // ── REST: create ──────────────────────────────────────────────────────────

    @PostMapping("/api/jobs")
    @ResponseBody
    public ResponseEntity<?> createJob(@RequestBody JobRequest req,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER createJob: [user={}, name={}]", user.getUsername(), req.name());
        String err = validateRequest(req);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        String groupId = req.groupId() == null ? "" : req.groupId().trim();
        String artifactId = req.artifactId() == null ? "" : req.artifactId().trim();
        String identityErr = validateArtifactIdentity(groupId, artifactId, SNAPSHOT_VERSION);
        if (identityErr != null) return ResponseEntity.badRequest().body(Map.of("error", identityErr));

        String vid = toVid(groupId, artifactId, SNAPSHOT_VERSION);
        if (jobRepository.get(vid) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Job artifact already exists: " + vid));
        }

        String skillErr = validateAssignableSkillUuids(req.skillUuids());
        if (skillErr != null) return ResponseEntity.badRequest().body(Map.of("error", skillErr));
        List<String> notificationUsers = normalizeUsernames(req.notificationUserIds());
        String notificationErr = validateNotificationUsers(notificationUsers);
        if (notificationErr != null) return ResponseEntity.badRequest().body(Map.of("error", notificationErr));

        ScheduledJob job = new ScheduledJob(
            vid,
                req.name(),
                req.aiPrompt(),
                null,
                user.getUsername(),
                req.invocationType(),
                parseInstant(req.startTime()),
                req.repeatDuration() > 0 ? req.repeatDuration() : 0,
                req.durationType() != null ? req.durationType() : DurationType.MINUTES,
                0L,
                0L,
                req.agentTemplateId(),
                req.provider(),
                req.modelId(),
                req.oobTimeoutMinutes() > 0 ? req.oobTimeoutMinutes() : 240,
                req.expectedOutput(),
                ScheduledJobStatus.WAITING,
                req.skillUuids(),
                req.toolIds(),
                notificationUsers,
                groupId,
                artifactId,
                SNAPSHOT_VERSION,
                ArtifactStatus.SNAPSHOT);

        ScheduledJob saved = schedulerService.scheduleJob(job);
        log.info("Job created [id={}, user={}, type={}]", saved.id(), user.getUsername(), saved.invocationType());
        return ResponseEntity.ok(saved);
    }

    // ── REST: update ──────────────────────────────────────────────────────────

    @PutMapping("/api/jobs/{id}")
    @ResponseBody
    public ResponseEntity<?> updateJob(@PathVariable String id,
                                       @RequestBody JobRequest req,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER updateJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(existing.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (!existing.isSnapshotMutable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT jobs can be edited."));
        }

        String err = validateRequest(req);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        if (req.groupId() != null && !req.groupId().isBlank()
                && !req.groupId().trim().equals(existing.groupId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupId is immutable after creation."));
        }
        if (req.artifactId() != null && !req.artifactId().isBlank()
                && !req.artifactId().trim().equals(existing.artifactId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "artifactId is immutable after creation."));
        }
        String skillErr = validateAssignableSkillUuids(req.skillUuids());
        if (skillErr != null) return ResponseEntity.badRequest().body(Map.of("error", skillErr));
        List<String> notificationUsers = req.notificationUserIds() == null
            ? existing.notificationUserIds()
            : normalizeUsernames(req.notificationUserIds());
        String notificationErr = validateNotificationUsers(notificationUsers);
        if (notificationErr != null) return ResponseEntity.badRequest().body(Map.of("error", notificationErr));

        ScheduledJob updated = new ScheduledJob(
                id,
                req.name(),
                req.aiPrompt(),
                existing.sessionUuid(),
                existing.userId(),
                req.invocationType(),
                parseInstant(req.startTime()),
                req.repeatDuration() > 0 ? req.repeatDuration() : 0,
                req.durationType() != null ? req.durationType() : DurationType.MINUTES,
                existing.lastExecutionTime(),
                existing.nextExecutionTime(),
                req.agentTemplateId(),
                req.provider(),
                req.modelId(),
                req.oobTimeoutMinutes() > 0 ? req.oobTimeoutMinutes() : existing.oobTimeoutMinutes(),
                req.expectedOutput(),
                existing.status(),
                req.skillUuids() != null ? req.skillUuids() : existing.skillUuids(),
                req.toolIds() != null ? req.toolIds() : existing.toolIds(),
                notificationUsers,
                existing.groupId(),
                existing.artifactId(),
                existing.version(),
                existing.artifactStatus());

        ScheduledJob saved = schedulerService.scheduleJob(updated);
        log.info("Job updated [id={}, user={}]", id, user.getUsername());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/api/jobs/update")
    @ResponseBody
    public ResponseEntity<?> updateJobLegacy(@RequestParam("id") String id,
                                             @RequestBody JobRequest req,
                                             @AuthenticationPrincipal UserDetails user) {
        return updateJob(id, req, user);
    }

    // ── REST: delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/api/jobs/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteJob(@PathVariable String id,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER deleteJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(existing.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (!existing.isDeletable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT, SUBMITTED, or REJECTED jobs can be deleted."));
        }

        schedulerService.deleteJob(id);
        log.info("Job deleted [id={}, user={}]", id, user.getUsername());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/api/jobs/delete")
    @ResponseBody
    public ResponseEntity<?> deleteJobLegacy(@RequestParam("id") String id,
                                             @AuthenticationPrincipal UserDetails user) {
        return deleteJob(id, user);
    }

    // ── REST: actions ─────────────────────────────────────────────────────────

    @PostMapping("/api/jobs/{id}/run")
    @ResponseBody
    public ResponseEntity<?> runNow(@PathVariable String id,
                                    @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER runNow: [id={}, user={}]", id, user.getUsername());
        ScheduledJob job = jobRepository.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(job.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));

        String trackingUuid = schedulerService.runNow(id);
        if (trackingUuid == null) return ResponseEntity.internalServerError().body(Map.of("error", "Failed to start job"));
        log.debug("EXIT runNow: [id={}, tracking={}]", id, trackingUuid);
        return ResponseEntity.ok(Map.of("ok", true, "trackingSessionUuid", trackingUuid));
    }

    @PostMapping("/api/jobs/run")
    @ResponseBody
    public ResponseEntity<?> runNowLegacy(@RequestParam("id") String id,
                                          @AuthenticationPrincipal UserDetails user) {
        return runNow(id, user);
    }

    @PostMapping("/api/jobs/sessions/{sessionUuid}/terminate")
    @ResponseBody
    public ResponseEntity<?> terminateSession(@PathVariable String sessionUuid,
                                              @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER terminateSession: [session={}, user={}]", sessionUuid, user.getUsername());
        AiSession session = sessionRepository.get(sessionUuid);
        if (session == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(session.username()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));

        sessionRepository.save(new AiSession(
                session.uuid(), session.provider(), session.originMode(),
                session.username(), session.name(), session.createdAt(),
                session.currentRoundCount(), session.messages(),
                session.environmentVariables(), AiSessionStatus.COMPLETED,
                session.activeAgentTemplateId(), session.modelId(),
                session.skillStack(), session.sessionSkillUuids(), session.sessionToolIds()));
        log.info("Session terminated by user [session={}, user={}]", sessionUuid, user.getUsername());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/jobs/{id}/pause")
    @ResponseBody
    public ResponseEntity<?> pauseJob(@PathVariable String id,
                                      @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER pauseJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob job = jobRepository.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(job.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (job.invocationType() == InvocationType.MANUAL)
            return ResponseEntity.badRequest().body(Map.of("error", "MANUAL jobs cannot be paused."));

        schedulerService.pauseJob(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/jobs/pause")
    @ResponseBody
    public ResponseEntity<?> pauseJobLegacy(@RequestParam("id") String id,
                                            @AuthenticationPrincipal UserDetails user) {
        return pauseJob(id, user);
    }

    @PostMapping("/api/jobs/{id}/resume")
    @ResponseBody
    public ResponseEntity<?> resumeJob(@PathVariable String id,
                                       @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER resumeJob: [id={}, user={}]", id, user.getUsername());
        ScheduledJob job = jobRepository.get(id);
        if (job == null) return ResponseEntity.notFound().build();
        if (!user.getUsername().equals(job.userId()))
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));

        schedulerService.resumeJob(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/jobs/resume")
    @ResponseBody
    public ResponseEntity<?> resumeJobLegacy(@RequestParam("id") String id,
                                             @AuthenticationPrincipal UserDetails user) {
        return resumeJob(id, user);
    }

    // ── Export / Import ──────────────────────────────────────────────────────

    @GetMapping("/api/jobs/{id}/export")
    @ResponseBody
    public ResponseEntity<?> exportJob(@PathVariable String id,
                                       @AuthenticationPrincipal UserDetails user) {
        
        ScheduledJob job = jobRepository.get(id);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (!user.getUsername().equals(job.userId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        AiSession session = null;
        if (job.sessionUuid() != null && !job.sessionUuid().isBlank()) {
            session = sessionRepository.get(job.sessionUuid());
        }

        JobExportPackage pkg = new JobExportPackage("1.0", toExportJob(job), session);
        String prettyJson;
        try {
            prettyJson = EXPORT_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pkg);
        } catch (JsonProcessingException ex) {
            log.warn("Job export JSON serialization failed [jobId={}]: {}", id, ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize export payload."));
        }
        String safeName = job.name() == null
                ? "job"
                : job.name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "job-" + safeName + ".json";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(prettyJson);
    }

    @GetMapping("/api/jobs/export")
    @ResponseBody
    public ResponseEntity<?> exportJobLegacy(@RequestParam("id") String id,
                                             @AuthenticationPrincipal UserDetails user) {
        return exportJob(id, user);
    }

    @PostMapping("/api/jobs/import")
    @ResponseBody
    public ResponseEntity<?> importJob(@RequestBody JobExportPackage pkg,
                                       @AuthenticationPrincipal UserDetails user) {
        if (pkg == null || pkg.job() == null || pkg.vorkJobExport() == null || pkg.vorkJobExport().isBlank()) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, "Invalid job export package."));
        }

        JobExportJob incoming = pkg.job();
        if (incoming.name() == null || incoming.name().isBlank()) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, "Name is required."));
        }
        if (incoming.aiPrompt() == null || incoming.aiPrompt().isBlank()) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, "AI prompt is required."));
        }
        if (incoming.invocationType() == null) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, "Invocation type is required."));
        }
        if (incoming.invocationType() == InvocationType.DYNAMIC) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null,
                    "DYNAMIC invocation type is internal and cannot be imported via Jobs API."));
        }
        if (incoming.invocationType() == InvocationType.REPEAT && incoming.repeatDuration() <= 0) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, "Repeat duration must be greater than zero."));
        }

        String skillErr = validateAssignableSkillUuids(incoming.skillUuids());
        if (skillErr != null) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, skillErr));
        }

        String groupId = incoming.groupId() == null ? "" : incoming.groupId().trim();
        String artifactId = incoming.artifactId() == null ? "" : incoming.artifactId().trim();
        String version = incoming.version() == null || incoming.version().isBlank()
                ? SNAPSHOT_VERSION
                : incoming.version().trim();
        String identityErr = validateArtifactIdentity(groupId, artifactId, version);
        if (identityErr != null) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, identityErr));
        }
        if (incoming.artifactStatus() != null && incoming.artifactStatus() != ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.badRequest().body(new JobImportResult("error", null, "Only SNAPSHOT jobs are importable in this flow."));
        }

        String incomingId = toVid(groupId, artifactId, version);
        if (incoming.id() != null && !incoming.id().isBlank() && !incomingId.equals(incoming.id().trim())) {
            return ResponseEntity.badRequest().body(new JobImportResult(
                    "error", incomingId, "Incoming id does not match deterministic VID."));
        }

        ScheduledJob existing = jobRepository.get(incomingId);
        boolean canUpdateInPlace = existing != null && user.getUsername().equals(existing.userId()) && existing.isSnapshotMutable();
        if (existing != null && !canUpdateInPlace) {
            return ResponseEntity.badRequest().body(new JobImportResult(
                    "error", incomingId, "Cannot overwrite existing non-SNAPSHOT or foreign-owned job artifact."));
        }
        String targetId = incomingId;

        String importedSessionUuid = null;
        if (pkg.session() != null) {
            importedSessionUuid = importTrackingSession(pkg.session(), user.getUsername());
        }

        ScheduledJob normalized = new ScheduledJob(
                targetId,
                incoming.name(),
                incoming.aiPrompt(),
                importedSessionUuid,
                user.getUsername(),
                incoming.invocationType(),
                Instant.now(),
                incoming.repeatDuration() > 0 ? incoming.repeatDuration() : 0,
                incoming.durationType() == null ? DurationType.MINUTES : incoming.durationType(),
                0L,
                0L,
                incoming.agentTemplateId(),
                incoming.provider(),
                incoming.modelId(),
                incoming.oobTimeoutMinutes() > 0 ? incoming.oobTimeoutMinutes() : 240,
                incoming.expectedOutput(),
                ScheduledJobStatus.WAITING,
                incoming.skillUuids() == null ? List.of() : List.copyOf(incoming.skillUuids()),
                incoming.toolIds() == null ? List.of() : List.copyOf(incoming.toolIds()),
            existing != null ? existing.notificationUserIds() : List.of(user.getUsername()),
                groupId,
                artifactId,
                version,
                ArtifactStatus.SNAPSHOT);

        ScheduledJob saved = schedulerService.scheduleJob(normalized);
        String status = canUpdateInPlace ? "updated" : "imported";
        return ResponseEntity.ok(new JobImportResult(status, saved.id(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<?> handleMalformedImportJson(HttpMessageNotReadableException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage();

        log.warn("Job import JSON parse failure: {}", detail, ex);

        return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Invalid JSON payload for job import.",
                "detail", detail
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String validateRequest(JobRequest req) {
        if (req.name() == null || req.name().isBlank()) return "Name is required.";
        if (req.aiPrompt() == null || req.aiPrompt().isBlank()) return "AI prompt is required.";
        if (req.invocationType() == null) return "Invocation type is required.";
        if (req.invocationType() == InvocationType.DYNAMIC)
            return "DYNAMIC invocation type is internal and cannot be created from the Jobs API.";
        if (req.invocationType() == InvocationType.REPEAT && req.repeatDuration() <= 0)
            return "Repeat duration must be greater than zero.";
        return null;
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return Instant.now();
        try {
            // datetime-local input gives "YYYY-MM-DDTHH:mm" — append seconds + Z if needed
            String s = iso.length() == 16 ? iso + ":00Z" : iso.endsWith("Z") ? iso : iso + "Z";
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static String validateArtifactIdentity(String groupId, String artifactId, String version) {
        if (groupId == null || groupId.isBlank()) {
            return "groupId is required.";
        }
        if (artifactId == null || artifactId.isBlank()) {
            return "artifactId is required.";
        }
        if (groupId.length() < GROUP_ID_MIN_LEN || groupId.length() > GROUP_ID_MAX_LEN) {
            return "groupId length must be between 3 and 64 characters.";
        }
        if (artifactId.length() < ARTIFACT_ID_MIN_LEN || artifactId.length() > ARTIFACT_ID_MAX_LEN) {
            return "artifactId length must be between 3 and 64 characters.";
        }
        if (!GROUP_ID_PATTERN.matcher(groupId).matches()) {
            return "groupId must be alphanumeric only (letters and numbers), with no spaces.";
        }
        if (!ARTIFACT_ID_PATTERN.matcher(artifactId).matches()) {
            return "artifactId must be alphanumeric only (letters and numbers), with no spaces.";
        }
        if (version == null || version.isBlank()) {
            return "version is required.";
        }
        if (version.length() > VERSION_MAX_LEN) {
            return "version length must be 16 characters or fewer.";
        }
        if (!SNAPSHOT_VERSION.equals(version)) {
            return "Only version SNAPSHOT is supported in this flow.";
        }
        return null;
    }

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }

    private String validateAssignableSkillUuids(List<String> skillUuids) {
        if (skillUuids == null || skillUuids.isEmpty()) {
            return null;
        }
        for (String skillUuid : skillUuids) {
            Skill skill = skillRepository.get(skillUuid);
            if (skill == null) {
                return "Unknown skill UUID: " + skillUuid;
            }
            if (skill.visibility() == SkillVisibility.PRIVATE) {
                return "Private skill cannot be attached to jobs: " + skill.name();
            }
        }
        return null;
    }

    private List<String> normalizeUsernames(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        return usernames.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String validateNotificationUsers(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return null;
        }
        List<String> validUsers = userManagementService.listUsers().stream()
                .map(UserManagementService.UserSummary::username)
                .toList();
        for (String username : usernames) {
            if (!validUsers.contains(username)) {
                return "Unknown notification user: " + username;
            }
        }
        return null;
    }

    private JobExportJob toExportJob(ScheduledJob job) {
        return new JobExportJob(
                job.id(),
                job.name(),
                job.aiPrompt(),
                job.invocationType(),
                job.repeatDuration(),
                job.durationType(),
                job.agentTemplateId(),
                job.provider(),
                job.modelId(),
                job.oobTimeoutMinutes(),
                job.expectedOutput(),
                job.skillUuids(),
                job.toolIds(),
                job.groupId(),
                job.artifactId(),
                job.version(),
                job.artifactStatus());
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    record JobRequest(
            String name,
            String aiPrompt,
            InvocationType invocationType,
            String startTime,          // ISO-8601 or datetime-local string
            long repeatDuration,
            DurationType durationType,
            String agentTemplateId,
            String provider,
            String modelId,
            int oobTimeoutMinutes,     // minutes before OOB relay auth link expires; 0 = default 240 (4 hrs)
            String expectedOutput,     // optional — describes required output; enforced via background protocol
            List<String> skillUuids,   // optional — extra skill UUIDs for this job's session
                List<String> toolIds,      // optional — extra tool bean IDs for this job's session
                List<String> notificationUserIds,
                String groupId,
                String artifactId
    ) {}

            record JobExportPackage(
                String vorkJobExport,
                    JobExportJob job,
                    AiSession session
            ) {}

            record JobExportJob(
                String id,
                String name,
                String aiPrompt,
                InvocationType invocationType,
                long repeatDuration,
                DurationType durationType,
                String agentTemplateId,
                String provider,
                String modelId,
                int oobTimeoutMinutes,
                String expectedOutput,
                List<String> skillUuids,
                List<String> toolIds,
                String groupId,
                String artifactId,
                String version,
                ArtifactStatus artifactStatus
            ) {}

            record JobImportResult(
                String status,
                String jobId,
                String message
            ) {}

        private String importTrackingSession(AiSession exportedSession,
                                             String username) {
            String newSessionUuid = UUID.randomUUID().toString();
            AiSession imported = new AiSession(
                    newSessionUuid,
                    exportedSession.provider(),
                    exportedSession.originMode() == null ? SessionOriginMode.WEB : exportedSession.originMode(),
                    username,
                    exportedSession.name(),
                    System.currentTimeMillis(),
                    0,
                    exportedSession.messages() == null ? List.<AiChatMessage>of() : List.copyOf(exportedSession.messages()),
                    exportedSession.environmentVariables(),
                    AiSessionStatus.RUNNING,
                    exportedSession.activeAgentTemplateId(),
                    exportedSession.modelId(),
                    exportedSession.skillStack() == null ? List.of() : List.copyOf(exportedSession.skillStack()),
                    exportedSession.sessionSkillUuids() == null ? List.of() : List.copyOf(exportedSession.sessionSkillUuids()),
                    exportedSession.sessionToolIds() == null ? List.of() : List.copyOf(exportedSession.sessionToolIds()));
            sessionRepository.save(imported);
            return newSessionUuid;
        }
}
