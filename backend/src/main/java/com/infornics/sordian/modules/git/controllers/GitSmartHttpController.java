package com.infornics.sordian.modules.git.controllers;

import com.infornics.sordian.modules.git.services.GitProcessRunner;
import com.infornics.sordian.modules.git.services.GitQuarantineService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the Git Smart HTTP transfer protocol (RFC / Git Documentation).
 * Endpoints:
 * - GET  /{org}/{repo}.git/info/refs?service=git-receive-pack
 * - POST /{org}/{repo}.git/git-receive-pack
 */
@RestController
@RequestMapping("/{org}/{repo}.git")
@RequiredArgsConstructor
public class GitSmartHttpController {

    private static final Logger log = LoggerFactory.getLogger(GitSmartHttpController.class);

    private static final String RECEIVE_PACK_SERVICE = "git-receive-pack";
    private static final String RECEIVE_PACK_CONTENT_TYPE = "application/x-git-receive-pack-result";
    private static final String RECEIVE_PACK_ADVERTISEMENT_TYPE = "application/x-git-receive-pack-advertisement";

    private final GitQuarantineService quarantineService;
    private final GitProcessRunner gitRunner;
    private final com.infornics.sordian.modules.git.services.GitPktLineParser pktLineParser;
    private final com.infornics.sordian.modules.git.services.GitInspectionService inspectionService;
    private final com.infornics.sordian.modules.git.services.GitUpstreamForwarderService upstreamForwarder;

    /**
     * Git Smart HTTP ref advertisement for git-receive-pack.
     */
    @GetMapping("/info/refs")
    public void getInfoRefs(
            @PathVariable("org") String org,
            @PathVariable("repo") String repo,
            @RequestParam(value = "service", required = false) String service,
            HttpServletResponse response) throws IOException, InterruptedException {

        if (!RECEIVE_PACK_SERVICE.equals(service)) {
            // Sordian only proxies write-path pushes (receive-pack). Clones/fetches go directly to GitHub.
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("text/plain");
            response.getWriter().write("Sordian only proxies Git pushes ('git-receive-pack'). Please fetch/clone directly from GitHub.\n");
            return;
        }

        File repoDir = quarantineService.getOrCreateQuarantineRepo(org, repo);

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(RECEIVE_PACK_ADVERTISEMENT_TYPE);
        response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");

        OutputStream out = response.getOutputStream();

        // 1. Git pkt-line service header: e.g. "001f# service=git-receive-pack\n0000"
        writePktLine(out, "# service=" + service + "\n");
        writePktFlush(out);

        // 2. Invoke git receive-pack --stateless-rpc --advertise-refs <repo-path>
        List<String> commandArgs = List.of(
                "receive-pack",
                "--stateless-rpc",
                "--advertise-refs",
                repoDir.getAbsolutePath()
        );

        gitRunner.execute(repoDir, commandArgs, null, null, out);
        out.flush();
    }

    /**
     * Git Smart HTTP receive-pack endpoint for inspecting commands and receiving packfiles.
     */
    @PostMapping("/git-receive-pack")
    public void receivePack(
            @PathVariable("org") String org,
            @PathVariable("repo") String repo,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException, InterruptedException {

        File repoDir = quarantineService.getOrCreateQuarantineRepo(org, repo);

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(RECEIVE_PACK_CONTENT_TYPE);
        response.setHeader("Cache-Control", "no-cache, max-age=0, must-revalidate");
        response.setHeader("Pragma", "no-cache");

        OutputStream responseOut = response.getOutputStream();

        // Step 1.3: Parse incoming ref commands before forwarding
        java.io.InputStream in = request.getInputStream();
        com.infornics.sordian.modules.git.services.GitPktLineParser.ParseResult parseResult =
                pktLineParser.parsePushCommands(in);

        List<com.infornics.sordian.modules.git.models.RefCommand> commands = parseResult.getCommands();

        // Empty flush push (test probes)
        if (commands.isEmpty()) {
            writePktFlush(responseOut);
            responseOut.flush();
            return;
        }

        // Validate proposed ref updates
        com.infornics.sordian.modules.git.models.PushValidationResult validation =
                inspectionService.validatePushCommands(repoDir, commands);

        if (!validation.isAllowed()) {
            log.info("Push rejected for {}/{}: {}", org, repo, validation.getRejectionReason());
            sendGitRejection(responseOut, validation, commands);
            responseOut.flush();
            return;
        }

        // Permitted: reconstruct stream (preamble bytes + remaining packfile) and stream into receive-pack
        java.io.SequenceInputStream combinedIn = new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(parseResult.getRawPreamble()),
                in
        );

        List<String> commandArgs = List.of(
                "receive-pack",
                "--stateless-rpc",
                repoDir.getAbsolutePath()
        );

        // Capture local receive-pack output to verify unpack
        java.io.ByteArrayOutputStream localReceiveOut = new java.io.ByteArrayOutputStream();
        int exitCode = gitRunner.execute(
                repoDir,
                commandArgs,
                Map.of(),
                combinedIn,
                localReceiveOut
        );

        if (exitCode != 0) {
            log.error("Local quarantine receive-pack failed for {}/{} with exit {}", org, repo, exitCode);
            writePktLine(responseOut, "unpack error unpack-failed\n");
            for (com.infornics.sordian.modules.git.models.RefCommand cmd : commands) {
                writePktLine(responseOut, "ng " + cmd.getRefName() + " unpack failed\n");
            }
            writePktFlush(responseOut);
            responseOut.flush();
            return;
        }

        // Step 1.4: Forward approved ref updates to upstream GitHub
        com.infornics.sordian.modules.git.models.UpstreamForwardResult forwardResult =
                upstreamForwarder.forwardRefs(repoDir, org, repo, commands);

        if (forwardResult.isSuccess()) {
            writePktLine(responseOut, "unpack ok\n");
            for (com.infornics.sordian.modules.git.models.RefCommand cmd : commands) {
                writePktLine(responseOut, "ok " + cmd.getRefName() + "\n");
            }
            writePktFlush(responseOut);
            log.info("Successfully received, verified, and forwarded push for {}/{}", org, repo);
        } else {
            log.warn("Upstream forwarding failed for {}/{}: {}", org, repo, forwardResult.getStandardError());
            writePktLine(responseOut, "unpack ok\n");
            for (com.infornics.sordian.modules.git.models.RefCommand cmd : commands) {
                writePktLine(responseOut, "ng " + cmd.getRefName() + " [SORDIAN UPSTREAM ERROR] " +
                        (forwardResult.getStandardError().isBlank() ? "Failed to forward to GitHub" : forwardResult.getStandardError().trim()) + "\n");
            }
            writePktFlush(responseOut);
        }

        responseOut.flush();
    }

    private void sendGitRejection(
            OutputStream out,
            com.infornics.sordian.modules.git.models.PushValidationResult validation,
            List<com.infornics.sordian.modules.git.models.RefCommand> commands) throws IOException {

        // Report status to Git client according to Git receive-pack wire protocol
        // 1. Unpack status
        writePktLine(out, "unpack ok\n");

        // 2. NG per ref status
        for (com.infornics.sordian.modules.git.models.RefCommand cmd : commands) {
            String errorMsg = validation.getRejectionReason();
            writePktLine(out, "ng " + cmd.getRefName() + " [SORDIAN REJECTED] " + errorMsg + "\n");
        }

        // 3. Flush packet to conclude report-status
        writePktFlush(out);
    }

    private void writePktLine(OutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int len = bytes.length + 4;
        String hexLen = String.format("%04x", len);
        out.write(hexLen.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
    }

    private void writePktFlush(OutputStream out) throws IOException {
        out.write("0000".getBytes(StandardCharsets.UTF_8));
    }
}
