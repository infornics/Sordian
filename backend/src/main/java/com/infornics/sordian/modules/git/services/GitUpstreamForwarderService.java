package com.infornics.sordian.modules.git.services;

import com.infornics.sordian.modules.git.models.RefCommand;
import com.infornics.sordian.modules.git.models.UpstreamForwardResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Forwards approved Git objects and refs from the local quarantine repository
 * to the authoritative upstream GitHub repository.
 */
@Service
@RequiredArgsConstructor
public class GitUpstreamForwarderService {

    private static final Logger log = LoggerFactory.getLogger(GitUpstreamForwarderService.class);

    private final GitProcessRunner gitRunner;

    @Value("${sordian.git.upstream-base-url:https://github.com}")
    private String upstreamBaseUrl;

    @Value("${sordian.git.upstream-token:}")
    private String upstreamToken;

    /**
     * Forwards the specified ref updates from the local quarantine repo to upstream GitHub.
     */
    public UpstreamForwardResult forwardRefs(
            File quarantineRepoDir,
            String org,
            String repo,
            List<RefCommand> commands) throws IOException, InterruptedException {

        String safeOrg = org.trim();
        String safeRepo = repo.trim().replaceAll("\\.git$", "");
        String targetUrl = resolveUpstreamUrl(safeOrg, safeRepo);

        // Build refspecs to push, e.g. "refs/heads/delete-this:refs/heads/delete-this"
        // or "<new-oid>:refs/heads/delete-this"
        List<String> pushArgs = new ArrayList<>(List.of("push"));
        
        // Prevent git from asking for interactive terminal credentials
        Map<String, String> env = Map.of(
                "GIT_TERMINAL_PROMPT", "0",
                "GIT_ASKPASS", "echo"
        );

        pushArgs.add(targetUrl);
        for (RefCommand cmd : commands) {
            // Push the new commit object ID to the target refspec
            pushArgs.add(cmd.getNewOid() + ":" + cmd.getRefName());
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        log.info("Forwarding {} ref(s) for {}/{} to upstream: {}", commands.size(), org, repo, targetUrl);

        int exitCode = gitRunner.execute(
                quarantineRepoDir,
                pushArgs,
                env,
                null,
                stdout,
                stderr
        );

        boolean success = (exitCode == 0);
        String errOutput = stderr.toString(StandardCharsets.UTF_8);
        String stdOutput = stdout.toString(StandardCharsets.UTF_8);

        if (!success) {
            log.warn("Upstream push to {}/{} failed with exit {}. Error output: {}", org, repo, exitCode, errOutput);
        } else {
            log.info("Successfully pushed {} ref(s) to upstream {}/{}", commands.size(), org, repo);
        }

        return UpstreamForwardResult.builder()
                .success(success)
                .exitCode(exitCode)
                .standardOutput(stdOutput)
                .standardError(errOutput)
                .upstreamUrl(targetUrl)
                .build();
    }

    private String resolveUpstreamUrl(String org, String repo) {
        if (upstreamToken != null && !upstreamToken.isBlank()) {
            // Format: https://x-access-token:<TOKEN>@github.com/org/repo.git
            return String.format("https://x-access-token:%s@github.com/%s/%s.git", upstreamToken, org, repo);
        }
        return String.format("%s/%s/%s.git", upstreamBaseUrl, org, repo);
    }
}
