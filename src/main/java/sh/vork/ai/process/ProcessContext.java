package sh.vork.ai.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a running process and its stream plumbing.
 */
public class ProcessContext {

    private static final Logger log = LoggerFactory.getLogger(ProcessContext.class);
    private static final int MAX_CHUNKS = 500;
    private static final int READ_BUFFER_SIZE = 1024;

    private final String pid;
    private final Process process;
    private final OutputStream stdin;
    private final LinkedBlockingDeque<String> outputChunks;
    private final Thread readerThread;

    public ProcessContext(String pid, Process process) {
        this.pid = pid;
        this.process = process;
        this.stdin = process.getOutputStream();
        this.outputChunks = new LinkedBlockingDeque<>(MAX_CHUNKS);
        this.readerThread = Thread.ofPlatform()
                .name("process-reader-" + pid)
                .daemon(true)
                .start(this::consumeOutput);
    }

    public Process process() {
        return process;
    }

    public OutputStream stdin() {
        return stdin;
    }

    public Thread readerThread() {
        return readerThread;
    }

    public String drainOutput(int timeoutSeconds) throws InterruptedException {
        List<String> chunks = new ArrayList<>();

        if (timeoutSeconds > 0) {
            String first = outputChunks.poll(timeoutSeconds, TimeUnit.SECONDS);
            if (first == null) {
                return "";
            }
            chunks.add(first);
        }

        outputChunks.drainTo(chunks);

        if (chunks.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder(chunks.stream().mapToInt(String::length).sum());
        for (String chunk : chunks) {
            out.append(chunk);
        }
        return out.toString();
    }

    public void appendInput(String text) throws IOException {
        stdin.write(text.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    public void close() {
        try {
            stdin.close();
        } catch (IOException ex) {
            log.debug("Ignoring stdin close error [pid={}]: {}", pid, ex.getMessage());
        }
        readerThread.interrupt();
    }

    private void consumeOutput() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        InputStream input = process.getInputStream();

        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                appendChunk(chunk);
            }
        } catch (IOException ex) {
            if (process.isAlive()) {
                log.warn("Process output reader failed [pid={}]: {}", pid, ex.getMessage());
            }
        }
    }

    private void appendChunk(String chunk) {
        while (!outputChunks.offerLast(chunk)) {
            outputChunks.pollFirst();
        }
    }
}
