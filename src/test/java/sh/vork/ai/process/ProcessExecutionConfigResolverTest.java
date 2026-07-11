package sh.vork.ai.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sh.vork.ai.memory.InMemorySessionEnvironmentService;

class ProcessExecutionConfigResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void apply_setsWorkingDirectoryAndMergesValidatedSessionCommandPaths() throws Exception {
        String sessionUuid = "session-proc-1";
        InMemorySessionEnvironmentService env = new InMemorySessionEnvironmentService();
        SessionPathResolver pathResolver = new SessionPathResolver(tempDir.toString());
        ProcessExecutionConfigResolver resolver = new ProcessExecutionConfigResolver(env, pathResolver);

        Path inRoot = pathResolver.sessionRoot(sessionUuid).resolve("tools/node/bin");
        Files.createDirectories(inRoot);

        Path outside = tempDir.resolve("outside/bin");
        Files.createDirectories(outside);

        env.setEnv(sessionUuid,
                ProcessExecutionConfigResolver.ENV_COMMAND_PATHS,
                inRoot + File.pathSeparator + outside);

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-lc", "echo ok");
        resolver.apply(builder, sessionUuid);

        assertEquals(pathResolver.toolsRoot(sessionUuid).toFile(), builder.directory());

        Map<String, String> processEnv = builder.environment();
        String mergedPath = processEnv.get("PATH");
        assertTrue(mergedPath.startsWith(inRoot.toString()));
        assertFalse(mergedPath.contains(outside.toString()));
        assertEquals(pathResolver.toolsRoot(sessionUuid).toString(), processEnv.get(ProcessExecutionConfigResolver.ENV_TOOLS_HOME));
        assertEquals(inRoot.toString(), processEnv.get(ProcessExecutionConfigResolver.ENV_COMMAND_PATHS));
    }

    @Test
    void normalizeCommandPathList_deduplicatesAndPreservesOrder() {
        String existing = "/a/bin" + File.pathSeparator + "/b/bin";
        String merged = ProcessExecutionConfigResolver.normalizeCommandPathList(existing, "/a/bin");
        assertEquals(existing, merged);

        String expanded = ProcessExecutionConfigResolver.normalizeCommandPathList(existing, "/c/bin");
        assertEquals(existing + File.pathSeparator + "/c/bin", expanded);
    }

    @Test
    void normalizeCommandName_lowerCasesAndTrims() {
        assertEquals("node", ProcessExecutionConfigResolver.normalizeCommandName("  NODE  "));
    }
}
