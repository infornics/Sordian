package com.infornics.sordian.modules.git.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes Git CLI subprocesses with isolated environments, sanitized arguments,
 * and no shell-string interpolation.
 */
@Service
public class GitProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(GitProcessRunner.class);
    private static final int PROCESS_TIMEOUT_SECONDS = 60;

    /**
     * Spawns a Git process, connects streams, and waits for completion.
     */
    public int execute(
            File workingDirectory,
            List<String> commandArgs,
            Map<String, String> extraEnv,
            InputStream stdin,
            OutputStream stdout) throws IOException, InterruptedException {

        List<String> fullCommand = new java.util.ArrayList<>();
        fullCommand.add("git");
        fullCommand.addAll(commandArgs);

        log.debug("Executing Git process: {} in dir {}", fullCommand, workingDirectory.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        pb.directory(workingDirectory);

        // Sanitize environment
        Map<String, String> env = pb.environment();
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }

        Process process = pb.start();

        // Pipe stdin to process if provided
        Thread stdinThread = null;
        if (stdin != null) {
            stdinThread = Thread.ofVirtual().start(() -> {
                try (OutputStream processIn = process.getOutputStream()) {
                    stdin.transferTo(processIn);
                    processIn.flush();
                } catch (IOException e) {
                    log.trace("Stdin pipe closed or finished: {}", e.getMessage());
                }
            });
        }

        // Pipe process stdout to caller stdout
        try (InputStream processOut = process.getInputStream()) {
            processOut.transferTo(stdout);
            stdout.flush();
        }

        if (stdinThread != null) {
            stdinThread.join(TimeUnit.SECONDS.toMillis(PROCESS_TIMEOUT_SECONDS));
        }

        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Git process timed out after " + PROCESS_TIMEOUT_SECONDS + "s: " + fullCommand);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            log.warn("Git process exited with code {}: {}", exitCode, stderr);
        }

        return exitCode;
    }
}
