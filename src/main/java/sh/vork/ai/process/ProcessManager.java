package sh.vork.ai.process;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Session-aware process state manager for non-interactive background tools.
 */
@Component
public class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);
    private final ProcessExecutionConfigResolver processExecutionConfigResolver;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ProcessContext>> sessions = new ConcurrentHashMap<>();

    public ProcessManager(ProcessExecutionConfigResolver processExecutionConfigResolver) {
        this.processExecutionConfigResolver = processExecutionConfigResolver;
    }

    public String start(String sessionUuid, String command) throws IOException {
        log.debug("ENTER start: session={}, command={}", sessionUuid, command);
        String pid = UUID.randomUUID().toString();
        ProcessBuilder builder = ShellCommandProcessBuilder.from(command);
        builder.redirectErrorStream(true);
        processExecutionConfigResolver.apply(builder, sessionUuid);

        Process process = builder.start();
        ProcessContext context = new ProcessContext(pid, process);

        sessionMap(sessionUuid).put(pid, context);
        log.debug("EXIT start: session={}, pid={}, alive={}", sessionUuid, pid, process.isAlive());
        return pid;
    }

    public ProcessContext get(String sessionUuid, String pid) {
        ConcurrentHashMap<String, ProcessContext> map = sessions.get(sessionUuid);
        return map == null ? null : map.get(pid);
    }

    public ProcessContext remove(String sessionUuid, String pid) {
        ConcurrentHashMap<String, ProcessContext> map = sessions.get(sessionUuid);
        return map == null ? null : map.remove(pid);
    }

    public boolean contains(String sessionUuid, String pid) {
        ConcurrentHashMap<String, ProcessContext> map = sessions.get(sessionUuid);
        return map != null && map.containsKey(pid);
    }

    public void stop(String sessionUuid, String pid) {
        log.debug("ENTER stop: session={}, pid={}", sessionUuid, pid);
        ProcessContext context = remove(sessionUuid, pid);
        if (context == null) {
            log.debug("EXIT stop: no context [session={}, pid={}]", sessionUuid, pid);
            return;
        }

        context.process().destroyForcibly();
        context.close();
        log.debug("EXIT stop: terminated [session={}, pid={}]", sessionUuid, pid);
    }

    private ConcurrentHashMap<String, ProcessContext> sessionMap(String sessionUuid) {
        return sessions.computeIfAbsent(sessionUuid, ignored -> new ConcurrentHashMap<>());
    }
}
