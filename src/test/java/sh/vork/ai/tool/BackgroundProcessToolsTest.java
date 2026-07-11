package sh.vork.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.CheckProcessRequest;
import sh.vork.ai.function.ExecuteCommandAndOutputRequest;
import sh.vork.ai.function.ReadProcessRequest;
import sh.vork.ai.function.StartProcessRequest;
import sh.vork.ai.function.StopProcessRequest;
import sh.vork.ai.function.WriteProcessRequest;
import sh.vork.ai.process.ProcessExecutionConfigResolver;
import sh.vork.ai.process.ProcessManager;

class BackgroundProcessToolsTest {

    private static final String SESSION_UUID = "process-session-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
        private final ProcessManager processManager = new ProcessManager(new ProcessExecutionConfigResolver());

        private final ExecuteCommandAndOutputTool executeSyncTool = new ExecuteCommandAndOutputTool(
            objectMapper,
            new ProcessExecutionConfigResolver());
    private final StartProcessTool startProcessTool = new StartProcessTool(processManager, objectMapper);
    private final CheckProcessTool checkProcessTool = new CheckProcessTool(processManager, objectMapper);
    private final WriteProcessTool writeProcessTool = new WriteProcessTool(processManager, objectMapper);
    private final ReadProcessTool readProcessTool = new ReadProcessTool(processManager, objectMapper);
    private final StopProcessTool stopProcessTool = new StopProcessTool(processManager, objectMapper);

    private final List<String> startedPids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MDC.put("sessionUuid", SESSION_UUID);
    }

    @AfterEach
    void tearDown() {
        for (String pid : startedPids) {
            processManager.stop(SESSION_UUID, pid);
        }
        startedPids.clear();
        MDC.clear();
    }

    @Test
    void executeCommandAndOutputReturnsExitCodeAndOutput() throws Exception {
        Map<String, Object> payload = parse(executeSyncTool.execute(new ExecuteCommandAndOutputRequest("printf 'hello'")));

        assertEquals(0, ((Number) payload.get("exit_code")).intValue());
        assertEquals("hello", payload.get("output"));
    }

    @Test
    void startWriteReadCheckAndStopProcess() throws Exception {
        Map<String, Object> started = parse(startProcessTool.execute(new StartProcessRequest("cat")));
        assertEquals("STARTED", started.get("status"));

        String pid = (String) started.get("pid");
        startedPids.add(pid);

        Map<String, Object> running = parse(checkProcessTool.execute(new CheckProcessRequest(pid)));
        assertEquals("RUNNING", running.get("status"));

        Map<String, Object> written = parse(writeProcessTool.execute(new WriteProcessRequest(pid, "ping")));
        assertEquals("WRITTEN", written.get("status"));

        Map<String, Object> output = parse(readProcessTool.execute(new ReadProcessRequest(pid, 2)));
        assertEquals("OK", output.get("status"));
        assertTrue(((String) output.get("output")).contains("ping"));

        Map<String, Object> stopped = parse(stopProcessTool.execute(new StopProcessRequest(pid)));
        assertEquals("TERMINATED", stopped.get("status"));
        startedPids.remove(pid);

        Map<String, Object> missing = parse(checkProcessTool.execute(new CheckProcessRequest(pid)));
        assertEquals("ERROR", missing.get("status"));
    }

    @Test
    void readProcessWithoutNewOutputReturnsNoNewOutputStatus() throws Exception {
        Map<String, Object> started = parse(startProcessTool.execute(new StartProcessRequest("sleep 2")));
        String pid = (String) started.get("pid");
        startedPids.add(pid);

        Map<String, Object> output = parse(readProcessTool.execute(new ReadProcessRequest(pid, 1)));
        assertEquals("NO_NEW_OUTPUT", output.get("status"));
        assertEquals(Boolean.TRUE, output.get("process_active"));
    }

    private Map<String, Object> parse(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }
}
