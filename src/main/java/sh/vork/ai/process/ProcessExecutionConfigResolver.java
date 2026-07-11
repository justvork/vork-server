package sh.vork.ai.process;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import sh.vork.ai.memory.InMemorySessionEnvironmentService;
import sh.vork.ai.memory.SessionEnvironmentService;

/**
 * Applies a locked-down working directory and session PATH overlay to ProcessBuilder.
 */
@Component
public class ProcessExecutionConfigResolver {

    public static final String ENV_TOOLS_HOME = "VORK_TOOLS_HOME";
    public static final String ENV_COMMAND_PATHS = "VORK_COMMAND_PATHS";

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutionConfigResolver.class);

    private final SessionEnvironmentService sessionEnvironmentService;
    private final SessionPathResolver sessionPathResolver;

    public ProcessExecutionConfigResolver(SessionEnvironmentService sessionEnvironmentService,
                                          SessionPathResolver sessionPathResolver) {
        this.sessionEnvironmentService = sessionEnvironmentService;
        this.sessionPathResolver = sessionPathResolver;
    }

    public ProcessExecutionConfigResolver() {
        this(new InMemorySessionEnvironmentService(), new SessionPathResolver());
    }

    public void apply(ProcessBuilder builder, String sessionUuid) {
        log.debug("ENTER apply: session={}", sessionUuid);
        try {
            Path toolsRoot = sessionPathResolver.toolsRoot(sessionUuid);
            Files.createDirectories(toolsRoot);
            builder.directory(toolsRoot.toFile());

            Map<String, String> sessionEnv = sessionEnvironmentService.getEnv(sessionUuid);
            String configuredPaths = sessionEnv.getOrDefault(ENV_COMMAND_PATHS, "");
            List<String> validatedPaths = validateRegisteredPaths(sessionUuid, configuredPaths);

            String existingPath = builder.environment().getOrDefault(
                    "PATH",
                    System.getenv().getOrDefault("PATH", ""));
            String mergedPath = mergePath(validatedPaths, existingPath);

            builder.environment().put("PATH", mergedPath);
            builder.environment().put(ENV_TOOLS_HOME, toolsRoot.toString());
            builder.environment().put(ENV_COMMAND_PATHS, String.join(File.pathSeparator, validatedPaths));

            log.debug("EXIT apply: session={}, workingDir={}, commandPathCount={}",
                    sessionUuid, toolsRoot, validatedPaths.size());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve process execution config: " + ex.getMessage(), ex);
        }
    }

    private List<String> validateRegisteredPaths(String sessionUuid, String configuredPaths) {
        Set<String> unique = new LinkedHashSet<>();
        Path sessionRoot = sessionPathResolver.sessionRoot(sessionUuid);

        for (String raw : splitPathList(configuredPaths)) {
            try {
                Path candidate = Path.of(raw).toAbsolutePath().normalize();
                if (!candidate.startsWith(sessionRoot)) {
                    log.warn("Ignoring command path outside session root [session={}, path={}]", sessionUuid, candidate);
                    continue;
                }
                if (!Files.isDirectory(candidate)) {
                    log.debug("Ignoring missing command directory [session={}, path={}]", sessionUuid, candidate);
                    continue;
                }
                unique.add(candidate.toString());
            } catch (Exception ex) {
                log.debug("Ignoring invalid command path token [session={}, token={}]: {}", sessionUuid, raw, ex.getMessage());
            }
        }

        return new ArrayList<>(unique);
    }

    private static List<String> splitPathList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String[] tokens = raw.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            String normalized = token == null ? "" : token.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static String mergePath(List<String> registered, String existing) {
        if (registered.isEmpty()) {
            return existing == null ? "" : existing;
        }

        String prefix = String.join(File.pathSeparator, registered);
        if (existing == null || existing.isBlank()) {
            return prefix;
        }
        return prefix + File.pathSeparator + existing;
    }

    public static String normalizeCommandPathList(String existing, String newPath) {
        Set<String> ordered = new LinkedHashSet<>();
        for (String token : splitPathList(existing)) {
            ordered.add(token);
        }
        if (newPath != null && !newPath.isBlank()) {
            ordered.add(newPath.trim());
        }
        return String.join(File.pathSeparator, ordered);
    }

    public static String normalizeCommandName(String command) {
        if (command == null) {
            return "";
        }
        return command.trim().toLowerCase(Locale.ROOT);
    }
}
