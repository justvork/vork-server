package sh.vork.ai.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sh.vork.ai.AiProvider;
import sh.vork.ai.function.DesignSkillRequest;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.ai.registry.ToolDescriptor;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillGroup;
import sh.vork.skill.SkillParameter;
import sh.vork.skill.SkillService;
import sh.vork.skill.SkillVisibility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds and optionally persists skills from natural-language requests.
 */
@Service
public class SkillAuthoringService {

    private static final Logger log = LoggerFactory.getLogger(SkillAuthoringService.class);
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_SELECTED_TOOLS = 12;
    private static final int MAX_SELECTED_SUB_SKILLS = 4;
    private static final int MAX_SIMILAR_SKILLS = 3;
        private static final int MIN_TOOL_SCORE = 3;
    private static final String SKILL_USAGE_NON_RUNTIME_INPUTS = "SKILL_USAGE_NON_RUNTIME_INPUTS:";
    private static final String SKILL_USAGE_GUIDANCE = "SKILL_USAGE_GUIDANCE:";

        private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "or", "the", "to", "for", "of", "in", "on", "at", "by", "with", "from",
            "is", "are", "be", "this", "that", "it", "as", "if", "then", "than", "into", "using",
            "create", "build", "make", "new", "skill", "tool", "please", "should", "need", "want"
        );

    private static final Map<String, List<String>> INTENT_HINTS = Map.of(
            "database", List.of("mongo", "mongodb", "sql", "database", "collection", "query", "document"),
            "ssh", List.of("ssh", "server", "terminal", "host", "shell", "remote", "command"),
            "notification", List.of("notify", "notification", "email", "sms", "alert", "message"),
            "http", List.of("http", "https", "api", "endpoint", "request", "rest", "webhook"),
            "typegen", List.of("schema", "type", "record", "compile", "class", "java type")
    );

    private final ToolRegistry toolRegistry;
    private final SkillService skillService;
    private final AiChatClientFactory aiChatClientFactory;
    private final ObjectMapper objectMapper;

    @Autowired
    public SkillAuthoringService(ToolRegistry toolRegistry,
                                 SkillService skillService,
                                 AiChatClientFactory aiChatClientFactory,
                                 ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.skillService = skillService;
        this.aiChatClientFactory = aiChatClientFactory;
        this.objectMapper = objectMapper;
    }

    public SkillAuthoringService(ToolRegistry toolRegistry,
                                 SkillService skillService) {
        this(toolRegistry, skillService, null, new ObjectMapper());
    }

    public SkillAuthoringResult designSkillFromRequest(String username, DesignSkillRequest req) {
        return processSkillRequest(username, req);
    }

    private SkillAuthoringResult processSkillRequest(String username, DesignSkillRequest req) {
        String requestText = req != null && req.request() != null ? req.request().trim() : "";
        if (requestText.isBlank()) {
            return new SkillAuthoringResult(
                    "error",
                    false,
                    "request is required",
                    true,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    null,
                    null,
                    false,
                    false,
                    "Auto-share recommendation unavailable because request is empty.",
                    null);
        }

        List<ToolDescriptor> catalog = toolRegistry.getAvailableTools().stream().toList();
        Map<String, ToolDescriptor> byId = catalog.stream()
                .collect(Collectors.toMap(ToolDescriptor::id, d -> d, (a, b) -> a, LinkedHashMap::new));

        Selection fallbackSelection = selectTools(requestText, catalog, byId);
        List<Skill> allSkills = skillService.list();
        List<Skill> subSkills = selectSubSkills(requestText, allSkills);

        String name = buildSkillName(req, requestText);
        String category = buildCategory(req, requestText);
        String author = buildAuthor(req, username);
        String description = "Auto-generated from user request: " + summarize(requestText, 180);
        GroupResolution groupResolution = resolveTargetGroup(req, category, author, requestText, false);
        SkillGroup targetGroup = groupResolution.group();
        String groupUuid = targetGroup != null ? targetGroup.uuid() : "";
        log.debug("Step 1: resolved target group [uuid={}, name={}, created={}]",
            targetGroup != null ? targetGroup.uuid() : "",
            targetGroup != null ? targetGroup.name() : groupResolution.proposedGroupName(),
            groupResolution.created());
        List<Skill> limitedSubSkills = subSkills.stream().limit(MAX_SELECTED_SUB_SKILLS).toList();
        List<Skill> similarSkills = selectSimilarSkills(requestText, allSkills);

        AiSkillDraft aiDraft = draftWithAi(requestText, catalog, limitedSubSkills, similarSkills, req);
        Selection aiSelection = selectToolsFromAiDraft(aiDraft, byId, requestText);
        List<String> allowedTools = aiSelection != null && !aiSelection.tools().isEmpty()
            ? aiSelection.tools()
            : applyLeastPrivilege(fallbackSelection.tools(), requestText);
        List<String> primaryToolMatches = aiSelection != null && !aiSelection.primaryMatches().isEmpty()
            ? aiSelection.primaryMatches()
            : fallbackSelection.primaryMatches();
        if (aiDraft != null && aiDraft.skillName() != null && !aiDraft.skillName().isBlank()) {
            name = aiDraft.skillName().trim();
        }
        if (aiDraft != null && aiDraft.description() != null && !aiDraft.description().isBlank()) {
            description = aiDraft.description().trim();
        }

        List<SkillParameter> parameters = (aiDraft != null && aiDraft.parameters() != null && !aiDraft.parameters().isEmpty())
                ? aiDraft.parameters()
                : inferParametersFromRequest(requestText, allowedTools);
        parameters = sanitizeRuntimeParameters(requestText, allowedTools, parameters, byId);

        SkillVisibility visibility = SkillVisibility.PUBLIC;
        if (req != null && req.visibility() != null) {
            visibility = req.visibility();
        }
        log.debug("Step 2: visibility selected [visibility={}]", visibility);

        String instructions = (aiDraft != null && aiDraft.instructions() != null && !aiDraft.instructions().isBlank())
                ? aiDraft.instructions().trim()
                : buildInstructions(requestText, allowedTools, limitedSubSkills, similarSkills, parameters);
        if (aiDraft != null && aiDraft.outputContract() != null && !aiDraft.outputContract().isBlank()) {
            if (!instructions.toLowerCase(Locale.ROOT).contains("output contract")) {
                instructions = instructions + "\n\nOutput contract:\n" + aiDraft.outputContract().trim();
            }
        }
        instructions = ensureToolUsageGuidance(allowedTools, instructions, byId);

        List<String> subSkillUuids = limitedSubSkills.stream().map(Skill::uuid).toList();
        List<String> similarSkillUuids = similarSkills.stream().map(Skill::uuid).toList();

        SkillService.SkillRequest skillRequest = new SkillService.SkillRequest(
                name,
                description,
                groupUuid,
                visibility,
                parameters,
                instructions,
                allowedTools,
                List.of(),
                subSkillUuids,
                List.of());

        boolean feasible = !allowedTools.isEmpty();
        String rationale = feasible
                ? "Feasible: matched available tool capabilities for the request."
                : "Not feasible: no available tools matched the request intent strongly enough.";

        return new SkillAuthoringResult(
                feasible ? "dry_run" : "impossible",
                feasible,
                rationale,
                true,
                null,
                null,
                allowedTools,
                primaryToolMatches,
                subSkillUuids,
                similarSkillUuids,
                catalog.stream().map(ToolDescriptor::id).toList(),
                false,
                targetGroup != null ? targetGroup.uuid() : null,
                targetGroup != null ? targetGroup.name() : groupResolution.proposedGroupName(),
                groupResolution.created(),
                true,
                "All skills are auto-shared within their group by default.",
                skillRequest);
    }

    private static Selection selectTools(String requestText,
                                         List<ToolDescriptor> catalog,
                                         Map<String, ToolDescriptor> byId) {
        Set<String> requestTokens = tokenize(requestText);
        String intent = detectPrimaryIntent(requestText);
        List<ScoredTool> scored = new ArrayList<>();

        for (ToolDescriptor d : catalog) {
            String haystack = ((d.id() == null ? "" : d.id()) + " "
                    + (d.name() == null ? "" : d.name()) + " "
                    + (d.friendlyName() == null ? "" : d.friendlyName()) + " "
                    + (d.description() == null ? "" : d.description())).toLowerCase(Locale.ROOT);

            int score = 0;
            if (containsTokenLike(requestText, d.id()) || containsTokenLike(requestText, d.name())) {
                score += 8;
            }

            if (intentBoost(intent, d) > 0) {
                score += intentBoost(intent, d);
            }

            for (String token : requestTokens) {
                if (token.length() < 3) {
                    continue;
                }
                if (haystack.contains(token)) {
                    score += 2;
                }
            }

            if (score >= MIN_TOOL_SCORE) {
                scored.add(new ScoredTool(d.id(), score));
            }
        }

        scored.sort(Comparator.comparingInt(ScoredTool::score).reversed().thenComparing(ScoredTool::toolId));
        List<String> primary = scored.stream().limit(MAX_SELECTED_TOOLS).map(ScoredTool::toolId).toList();

        LinkedHashSet<String> expanded = new LinkedHashSet<>(primary);
        ArrayDeque<String> queue = new ArrayDeque<>(primary);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            ToolDescriptor descriptor = byId.get(current);
            if (descriptor == null || descriptor.dependsOn() == null) {
                continue;
            }
            for (String dep : descriptor.dependsOn()) {
                if (dep != null && !dep.isBlank() && expanded.add(dep)) {
                    queue.addLast(dep);
                }
            }
        }

        return new Selection(List.copyOf(expanded), primary);
    }

    private static List<Skill> selectSubSkills(String requestText, List<Skill> candidates) {
        Set<String> requestTokens = tokenize(requestText);
        return candidates.stream()
                .map(skill -> Map.entry(skill, scoreSkill(skill, requestText, requestTokens)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(MAX_SELECTED_SUB_SKILLS)
                .map(Map.Entry::getKey)
                .toList();
    }

            private static List<Skill> selectSimilarSkills(String requestText, List<Skill> candidates) {
        Set<String> requestTokens = tokenize(requestText);
        return candidates.stream()
            .map(skill -> Map.entry(skill, scoreSkill(skill, requestText, requestTokens)))
            .filter(entry -> entry.getValue() > 0)
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(MAX_SIMILAR_SKILLS)
            .map(Map.Entry::getKey)
            .toList();
        }

    private static int scoreSkill(Skill skill, String requestText, Set<String> requestTokens) {
        int score = 0;
        if (containsTokenLike(requestText, skill.name())) {
            score += 6;
        }

        if (containsTokenLike(requestText, skill.description())) {
            score += 3;
        }

        String toolText = skill.allowedTools() == null ? "" : String.join(" ", skill.allowedTools());
        String parameterText = skill.parameters() == null
                ? ""
                : skill.parameters().stream().map(SkillParameter::name).collect(Collectors.joining(" "));

        String haystack = (skill.name() + " " + skill.description() + " " + skill.instructions() + " " + toolText + " " + parameterText)
                .toLowerCase(Locale.ROOT);
        for (String token : requestTokens) {
            if (token.length() < 4) {
                continue;
            }
            if (haystack.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private static boolean containsTokenLike(String text, String candidate) {
        if (text == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        String normalizedCandidate = candidate.toLowerCase(Locale.ROOT);
        return normalizedText.contains(normalizedCandidate);
    }

    private static Set<String> tokenize(String input) {
        if (input == null || input.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(input.toLowerCase(Locale.ROOT))) {
            if (!token.isBlank() && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String buildSkillName(DesignSkillRequest req, String requestText) {
        if (req != null && req.skillName() != null && !req.skillName().isBlank()) {
            return req.skillName().trim();
        }

        List<String> words = tokenize(requestText).stream().limit(6).toList();
        if (words.isEmpty()) {
            return "Generated Skill";
        }

        String joined = words.stream()
                .map(SkillAuthoringService::titleCase)
                .collect(Collectors.joining(" "));
        return joined + " Skill";
    }

    private static String buildCategory(DesignSkillRequest req, String requestText) {
        if (req != null && req.category() != null && !req.category().isBlank()) {
            return req.category().trim();
        }
        String lower = requestText.toLowerCase(Locale.ROOT);
        if (lower.contains("ssh") || lower.contains("server") || lower.contains("terminal")) {
            return "Operations";
        }
        if (lower.contains("database") || lower.contains("mongo") || lower.contains("sql")) {
            return "Data";
        }
        return "Automation";
    }

    private static String buildAuthor(DesignSkillRequest req, String username) {
        if (req != null && req.author() != null && !req.author().isBlank()) {
            return req.author().trim();
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        return "Concierge";
    }

    private static List<String> applyLeastPrivilege(List<String> selectedTools, String requestText) {
        if (selectedTools == null || selectedTools.isEmpty()) {
            return List.of();
        }

        Set<String> requestTokens = tokenize(requestText);
        LinkedHashSet<String> guarded = new LinkedHashSet<>();
        for (String tool : selectedTools) {
            if (tool == null || tool.isBlank()) {
                continue;
            }

            String lower = tool.toLowerCase(Locale.ROOT);

            boolean mutating = lower.startsWith("delete")
                    || lower.startsWith("update")
                    || lower.startsWith("create")
                    || lower.startsWith("save")
                    || lower.startsWith("insert")
                    || lower.startsWith("send");

            if (!mutating) {
                guarded.add(tool);
                continue;
            }

            if (requestTokens.contains("delete") || requestTokens.contains("update") || requestTokens.contains("create")
                    || requestTokens.contains("save") || requestTokens.contains("insert") || requestTokens.contains("send")
                    || requestTokens.contains("write") || requestTokens.contains("configure")
                    || requestTokens.contains("config") || requestTokens.contains("setup")
                    || requestTokens.contains("connect") || requestTokens.contains("connection")) {
                guarded.add(tool);
            }
        }

        if (guarded.isEmpty()) {
            return selectedTools.stream().limit(3).toList();
        }
        return guarded.stream().limit(MAX_SELECTED_TOOLS).toList();
    }

    private static List<SkillParameter> sanitizeRuntimeParameters(String requestText,
                                                                  List<String> selectedTools,
                                                                  List<SkillParameter> parameters,
                                                                  Map<String, ToolDescriptor> byId) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }

        Set<String> nonRuntimeInputNames = collectNonRuntimeInputNames(selectedTools, byId);
        if (nonRuntimeInputNames.isEmpty()) {
            return List.copyOf(parameters);
        }

        List<SkillParameter> sanitized = new ArrayList<>();
        for (SkillParameter parameter : parameters) {
            if (parameter == null || parameter.name() == null || parameter.name().isBlank()) {
                continue;
            }
            String normalized = parameter.name().replaceAll("[^a-zA-Z0-9]", "")
                    .toLowerCase(Locale.ROOT);
            if (nonRuntimeInputNames.contains(normalized)) {
                continue;
            }
            sanitized.add(parameter);
        }

        if (sanitized.isEmpty()) {
            return inferParametersFromRequest(requestText, selectedTools);
        }
        return List.copyOf(sanitized);
    }

    private static String ensureToolUsageGuidance(List<String> selectedTools,
                                                  String instructions,
                                                  Map<String, ToolDescriptor> byId) {
        String base = instructions == null ? "" : instructions.trim();
        List<String> guidanceLines = collectSkillUsageGuidance(selectedTools, byId);
        if (guidanceLines.isEmpty()) {
            return base;
        }

        String lower = base.toLowerCase(Locale.ROOT);
        StringBuilder guidance = new StringBuilder("Tool usage guidance:\n");
        for (String line : guidanceLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (lower.contains(line.toLowerCase(Locale.ROOT))) {
                continue;
            }
            guidance.append("- ").append(line.trim()).append("\n");
        }

        if (guidance.toString().equals("Tool usage guidance:\n")) {
            return base;
        }

        if (base.isBlank()) {
            return guidance.toString().trim();
        }
        return base + "\n\n" + guidance.toString().trim();
    }

    private static Set<String> collectNonRuntimeInputNames(List<String> selectedTools,
                                                            Map<String, ToolDescriptor> byId) {
        if (selectedTools == null || selectedTools.isEmpty() || byId == null || byId.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String toolId : selectedTools) {
            if (toolId == null || toolId.isBlank()) {
                continue;
            }
            ToolDescriptor descriptor = byId.get(toolId);
            if (descriptor == null || descriptor.description() == null) {
                continue;
            }
            out.addAll(parseCsvPolicyValues(descriptor.description(), SKILL_USAGE_NON_RUNTIME_INPUTS));
        }
        return Set.copyOf(out);
    }

    private static List<String> collectSkillUsageGuidance(List<String> selectedTools,
                                                           Map<String, ToolDescriptor> byId) {
        if (selectedTools == null || selectedTools.isEmpty() || byId == null || byId.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String toolId : selectedTools) {
            if (toolId == null || toolId.isBlank()) {
                continue;
            }
            ToolDescriptor descriptor = byId.get(toolId);
            if (descriptor == null || descriptor.description() == null) {
                continue;
            }
            out.addAll(parsePolicyTextLines(descriptor.description(), SKILL_USAGE_GUIDANCE));
        }
        return List.copyOf(out);
    }

    private static Set<String> parseCsvPolicyValues(String description, String marker) {
        if (description == null || description.isBlank() || marker == null || marker.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : description.split("\\R")) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.regionMatches(true, 0, marker, 0, marker.length())) {
                continue;
            }
            String values = trimmed.substring(marker.length()).trim();
            for (String token : values.split(",")) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                String normalized = token.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
                if (!normalized.isBlank()) {
                    out.add(normalized);
                }
            }
        }
        return Set.copyOf(out);
    }

    private static List<String> parsePolicyTextLines(String description, String marker) {
        if (description == null || description.isBlank() || marker == null || marker.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : description.split("\\R")) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.regionMatches(true, 0, marker, 0, marker.length())) {
                continue;
            }
            String value = trimmed.substring(marker.length()).trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static List<SkillParameter> inferParametersFromRequest(String requestText, List<String> selectedTools) {
        String lower = requestText == null ? "" : requestText.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, SkillParameter> params = new LinkedHashMap<>();

        if (lower.contains("host") || lower.contains("server") || lower.contains("ssh") || hasToolPrefix(selectedTools, "ssh")) {
            params.put("target", new SkillParameter("target", "string", "Target host, alias, or node identifier"));
        }
        if (lower.contains("query") || lower.contains("search") || lower.contains("find") || hasToolContaining(selectedTools, "search")) {
            params.put("query", new SkillParameter("query", "string", "Search/filter expression for the requested operation"));
        }
        if (lower.contains("collection") || lower.contains("database") || lower.contains("mongo")) {
            params.put("collection", new SkillParameter("collection", "string", "Collection/table name when applicable"));
        }
        if (lower.contains("path") || lower.contains("file") || hasToolContaining(selectedTools, "file")) {
            params.put("path", new SkillParameter("path", "string", "File path or resource path when applicable"));
        }
        if (lower.contains("since") || lower.contains("from") || lower.contains("date") || lower.contains("time")) {
            params.put("since", new SkillParameter("since", "string", "Optional time/date boundary (e.g. 2026-06-01T00:00:00Z)"));
        }

        if (params.isEmpty()) {
            params.put("input", new SkillParameter("input", "string", "Primary input for this skill request"));
        }

        return List.copyOf(params.values());
    }

    private static String buildInstructions(String requestText,
                                            List<String> tools,
                                            List<Skill> subSkills,
                                            List<Skill> similarSkills,
                                            List<SkillParameter> parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an autonomous skill generated from a natural-language request. ");
        sb.append("Fulfill the objective exactly and return a concise, factual result.\n\n");
        sb.append("Objective:\n").append(requestText).append("\n\n");

        if (parameters != null && !parameters.isEmpty()) {
            sb.append("Input parameters provided at runtime:\n");
            for (SkillParameter parameter : parameters) {
                sb.append("- ").append(parameter.name()).append(" (").append(parameter.type()).append(")");
                if (parameter.description() != null && !parameter.description().isBlank()) {
                    sb.append(": ").append(parameter.description());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!tools.isEmpty()) {
            sb.append("You may use only these tools:\n");
            for (String tool : tools) {
                sb.append("- ").append(tool).append("\n");
            }
            sb.append("\n");
        }

        appendExecutionPlan(sb, requestText, tools);
        appendOutputContract(sb, requestText, parameters);

        if (!subSkills.isEmpty()) {
            sb.append("Available sub-skills:\n");
            for (Skill sub : subSkills) {
                sb.append("- ").append(sub.toolName()).append(": ").append(sub.description()).append("\n");
            }
            sb.append("\n");
        }

        if (similarSkills != null && !similarSkills.isEmpty()) {
            sb.append("Similar existing skills (reference their style and structure):\n");
            for (Skill similar : similarSkills) {
                sb.append("- ").append(similar.name())
                        .append(" [group=").append(similar.groupUuid()).append("]")
                        .append(" params=").append(similar.parameters().stream().map(SkillParameter::name).toList())
                        .append(" tools=").append(similar.allowedTools())
                        .append(" instructions=")
                        .append(summarize(similar.instructions(), 120))
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("When complete, return FINISHED_TURN with the final result only.");
        return sb.toString();
    }

    private static void appendExecutionPlan(StringBuilder sb, String requestText, List<String> tools) {
        sb.append("Execution strategy:\n");
        sb.append("1. Validate required input parameters for the requested operation before any external calls.\n");
        sb.append("2. Select the minimum required tools and execute them in deterministic order.\n");
        sb.append("3. Capture tool outputs, transform into the requested result, and report any recoverable errors with context.\n\n");
    }

    private static void appendOutputContract(StringBuilder sb,
                                             String requestText,
                                             List<SkillParameter> parameters) {
        sb.append("Output contract:\n");
        sb.append("Return JSON with fields: status, summary, result, and errors.\n");
        if (parameters != null && !parameters.isEmpty()) {
            sb.append("Ensure result reflects the provided runtime parameters exactly.\n");
        }
        sb.append("\n");
    }

    private static String summarize(String text, int max) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "...";
    }

    private static String titleCase(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        return Character.toUpperCase(token.charAt(0)) + token.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String detectPrimaryIntent(String requestText) {
        String lower = requestText == null ? "" : requestText.toLowerCase(Locale.ROOT);
        String bestIntent = "";
        int bestScore = -1;

        for (Map.Entry<String, List<String>> entry : INTENT_HINTS.entrySet()) {
            int score = 0;
            for (String hint : entry.getValue()) {
                if (lower.contains(hint)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIntent = entry.getKey();
            }
        }
        return bestScore > 0 ? bestIntent : "";
    }

    private static int intentBoost(String intent, ToolDescriptor descriptor) {
        if (intent == null || intent.isBlank() || descriptor == null) {
            return 0;
        }
        String haystack = ((descriptor.id() == null ? "" : descriptor.id()) + " "
                + (descriptor.name() == null ? "" : descriptor.name()) + " "
                + (descriptor.description() == null ? "" : descriptor.description())).toLowerCase(Locale.ROOT);

        List<String> hints = INTENT_HINTS.get(intent);
        if (hints == null || hints.isEmpty()) {
            return 0;
        }

        int boost = 0;
        for (String hint : hints) {
            if (haystack.contains(hint)) {
                boost += 2;
            }
        }
        return boost;
    }

    private static boolean hasToolPrefix(List<String> tools, String prefix) {
        if (tools == null || tools.isEmpty() || prefix == null || prefix.isBlank()) {
            return false;
        }
        return tools.stream().anyMatch(t -> t != null && t.toLowerCase(Locale.ROOT).startsWith(prefix));
    }

    private static boolean hasToolContaining(List<String> tools, String token) {
        if (tools == null || tools.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        return tools.stream().anyMatch(t -> t != null && t.toLowerCase(Locale.ROOT).contains(token));
    }

    private AiSkillDraft draftWithAi(String requestText,
                                     List<ToolDescriptor> toolCatalog,
                                     List<Skill> selectedSubSkills,
                                     List<Skill> similarSkills,
                                     DesignSkillRequest req) {
        if (aiChatClientFactory == null) {
            return null;
        }

        ChatClient base = aiChatClientFactory.getBaseClient(AiProvider.GEMINI);
        if (base == null) {
            log.debug("AI draft unavailable: no configured GEMINI client.");
            return null;
        }

        try {
            String systemPrompt = """
You are a skill-design planner.
Given a user's natural-language request, produce a broad, reusable skill design.

Requirements:
0. Select the minimum required tool IDs from the provided catalog.
1. Infer concrete input parameters needed to execute the skill.
2. Write deterministic execution instructions referencing only provided tool IDs.
3. Define an explicit output contract describing required response fields.
4. Keep instructions broad and provider-agnostic; do not hardcode product-specific endpoints unless requested.

Return strict JSON only with this schema:
{
    "selectedTools": ["toolId"],
  "skillName": "string",
  "description": "string",
  "parameters": [{"name":"string","type":"string","description":"string"}],
  "instructions": "string",
  "outputContract": "string",
  "autoShareWithinGroup": true
}
""";

            String similar = similarSkills == null ? "[]" : similarSkills.stream()
                    .limit(MAX_SIMILAR_SKILLS)
                    .map(s -> "- " + s.name() + " | tools=" + s.allowedTools() + " | params="
                            + s.parameters().stream().map(SkillParameter::name).toList())
                    .collect(Collectors.joining("\n"));

            String subSkills = selectedSubSkills == null ? "[]" : selectedSubSkills.stream()
                    .limit(MAX_SELECTED_SUB_SKILLS)
                    .map(Skill::name)
                    .collect(Collectors.joining(", "));

                String availableTools = toolCatalog == null ? "[]" : toolCatalog.stream()
                    .map(t -> "- " + t.id() + " | dependsOn=" + t.dependsOn() + " | description=" + summarize(t.description(), 120))
                    .collect(Collectors.joining("\n"));

            String userPrompt = """
Request:
%s

Available tools (you may reference only these tool IDs):
%s

Candidate sub-skills:
%s

Similar existing skills:
%s

Optional explicit fields:
- skillName: %s
- category: %s
- targetGroup: %s
- author: %s
""".formatted(
                    requestText,
                    availableTools,
                    subSkills,
                    similar,
                    req != null ? req.skillName() : "",
                    req != null ? req.category() : "",
                    req != null ? req.targetGroup() : "",
                    req != null ? req.author() : ""
            );

            String content = base.mutate()
                    .defaultSystem(systemPrompt)
                    .build()
                    .prompt()
                    .user(userPrompt)
                    .call()
                    .content();

            if (content == null || content.isBlank()) {
                return null;
            }

            String json = extractJsonObject(content);
            JsonNode root = objectMapper.readTree(json);

            List<SkillParameter> params = new ArrayList<>();
            JsonNode paramArray = root.path("parameters");
            if (paramArray.isArray()) {
                for (JsonNode node : paramArray) {
                    String name = node.path("name").asText("").trim();
                    if (name.isBlank()) {
                        continue;
                    }
                    String type = node.path("type").asText("string").trim();
                    String desc = node.path("description").asText("").trim();
                    params.add(new SkillParameter(name, type.isBlank() ? "string" : type, desc));
                }
            }

            Boolean autoShare = root.has("autoShareWithinGroup") && !root.get("autoShareWithinGroup").isNull()
                    ? root.get("autoShareWithinGroup").asBoolean()
                    : null;

            List<String> selectedToolIds = new ArrayList<>();
            JsonNode selectedToolsNode = root.path("selectedTools");
            if (selectedToolsNode.isArray()) {
                for (JsonNode node : selectedToolsNode) {
                    String id = node.asText("").trim();
                    if (!id.isBlank()) {
                        selectedToolIds.add(id);
                    }
                }
            }

            return new AiSkillDraft(
                    selectedToolIds,
                    root.path("skillName").asText(""),
                    root.path("description").asText(""),
                    params,
                    root.path("instructions").asText(""),
                    root.path("outputContract").asText(""),
                    autoShare
            );
        } catch (Exception ex) {
            log.warn("AI draft generation failed, using deterministic fallback: {}", ex.getMessage());
            return null;
        }
    }

    private static String extractJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```") && text.endsWith("```")) {
            int firstBrace = text.indexOf('{');
            int lastBrace = text.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return text.substring(firstBrace, lastBrace + 1);
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private static Selection selectToolsFromAiDraft(AiSkillDraft aiDraft,
                                                     Map<String, ToolDescriptor> byId,
                                                     String requestText) {
        if (aiDraft == null || aiDraft.selectedToolIds() == null || aiDraft.selectedToolIds().isEmpty()) {
            return null;
        }

        LinkedHashSet<String> primary = new LinkedHashSet<>();
        for (String rawId : aiDraft.selectedToolIds()) {
            if (rawId == null || rawId.isBlank()) {
                continue;
            }
            String id = rawId.trim();
            if (byId.containsKey(id)) {
                primary.add(id);
            }
        }
        if (primary.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> expanded = new LinkedHashSet<>(primary);
        ArrayDeque<String> queue = new ArrayDeque<>(primary);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            ToolDescriptor descriptor = byId.get(current);
            if (descriptor == null || descriptor.dependsOn() == null) {
                continue;
            }
            for (String dep : descriptor.dependsOn()) {
                if (dep != null && !dep.isBlank() && expanded.add(dep)) {
                    queue.addLast(dep);
                }
            }
        }

        List<String> normalized = applyLeastPrivilege(expanded.stream().limit(MAX_SELECTED_TOOLS).toList(), requestText);
        if (normalized.isEmpty()) {
            return null;
        }

        List<String> primaryMatches = primary.stream().filter(normalized::contains).toList();
        if (primaryMatches.isEmpty()) {
            primaryMatches = normalized;
        }
        return new Selection(normalized, primaryMatches);
    }

    private GroupResolution resolveTargetGroup(DesignSkillRequest req,
                                               String category,
                                               String author,
                                               String requestText,
                                               boolean createIfMissing) {
        List<SkillGroup> groups = skillService.listGroups();
        String explicitTarget = req != null && req.targetGroup() != null ? req.targetGroup().trim() : "";

        if (!explicitTarget.isBlank()) {
            for (SkillGroup group : groups) {
                if (group.uuid().equalsIgnoreCase(explicitTarget)
                        || group.name().equalsIgnoreCase(explicitTarget)) {
                    return new GroupResolution(group, false, group.name());
                }
            }
        }

        for (SkillGroup group : groups) {
            if (group.category() != null && group.category().equalsIgnoreCase(category)) {
                return new GroupResolution(group, false, group.name());
            }
        }

        String lowerRequest = requestText == null ? "" : requestText.toLowerCase(Locale.ROOT);
        for (SkillGroup group : groups) {
            if (group.name() != null && !group.name().isBlank()
                    && lowerRequest.contains(group.name().toLowerCase(Locale.ROOT))) {
                return new GroupResolution(group, false, group.name());
            }
        }

        if (!explicitTarget.isBlank()) {
            String explicitLower = explicitTarget.toLowerCase(Locale.ROOT);
            for (SkillGroup group : groups) {
                if (group.name() != null && group.name().toLowerCase(Locale.ROOT).contains(explicitLower)) {
                    return new GroupResolution(group, false, group.name());
                }
            }
        }

        if (!createIfMissing) {
            String proposedName = !explicitTarget.isBlank()
                    ? explicitTarget
                    : normalizeGroupName((category == null || category.isBlank()) ? "Automation Skills" : category + " Skills");
            return new GroupResolution(null, false, proposedName);
        }

        String normalizedCategory = (category == null || category.isBlank()) ? "Automation" : category.trim();
        String groupName = !explicitTarget.isBlank()
                ? normalizeGroupName(explicitTarget)
                : normalizeGroupName(normalizedCategory + " Skills");
        SkillService.SkillGroupRequest groupRequest = new SkillService.SkillGroupRequest(
                groupName,
                author == null ? "" : author,
                normalizedCategory);
        return new GroupResolution(skillService.createGroup(groupRequest), true, groupName);
    }

    private static String normalizeGroupName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Automation Skills";
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private record Selection(List<String> tools, List<String> primaryMatches) {}

    private record ScoredTool(String toolId, int score) {}

    private record GroupResolution(SkillGroup group, boolean created, String proposedGroupName) {}

        private record AiSkillDraft(
            List<String> selectedToolIds,
            String skillName,
            String description,
            List<SkillParameter> parameters,
            String instructions,
            String outputContract,
            Boolean autoShareWithinGroup
        ) {}

    public record SkillAuthoringResult(
            String status,
            boolean feasible,
            String rationale,
            boolean dryRun,
            String skillUuid,
            String skillName,
            List<String> selectedTools,
            List<String> primaryToolMatches,
            List<String> selectedSubSkillUuids,
            List<String> selectedSimilarSkillUuids,
            List<String> availableToolIds,
            boolean attachedToConcierge,
            String resolvedGroupUuid,
            String resolvedGroupName,
            boolean groupCreated,
            boolean recommendedAutoShareWithinGroup,
            String autoShareRecommendation,
            SkillService.SkillRequest generatedSkillRequest
    ) {}
}
