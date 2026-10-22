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
     * Git Smart HTTP receive-pack endpoint for receiving packfiles.
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

        List<String> commandArgs = List.of(
                "receive-pack",
                "--stateless-rpc",
                repoDir.getAbsolutePath()
        );

        int exitCode = gitRunner.execute(
                repoDir,
                commandArgs,
                Map.of(),
                request.getInputStream(),
                responseOut
        );

        responseOut.flush();
        log.debug("Completed git-receive-pack for {}/{} with exit code {}", org, repo, exitCode);
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
