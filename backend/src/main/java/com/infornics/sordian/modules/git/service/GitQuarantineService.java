package com.infornics.sordian.modules.git.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages scratch quarantine workspaces on disk for receiving push packfiles.
 */
@Service
public class GitQuarantineService {

    private static final Logger log = LoggerFactory.getLogger(GitQuarantineService.class);

    private final Path quarantineRoot;
    private final GitProcessRunner gitRunner;

    public GitQuarantineService(
            @Value("${sordian.git.quarantine-dir:/tmp/sordian/quarantine}") String quarantineDir,
            GitProcessRunner gitRunner) throws IOException {
        this.quarantineRoot = Path.of(quarantineDir);
        this.gitRunner = gitRunner;
        Files.createDirectories(this.quarantineRoot);
    }

    /**
     * Resolves or initializes a temporary bare repository for the given org/repo
     * within the quarantine root directory.
     */
    public File getOrCreateQuarantineRepo(String org, String repo) throws IOException, InterruptedException {
        // Sanitize path identifiers to prevent directory traversal
        String safeOrg = sanitizeName(org);
        String safeRepo = sanitizeName(repo);

        Path repoPath = quarantineRoot.resolve(safeOrg).resolve(safeRepo + ".git");
        File repoDir = repoPath.toFile();

        if (!repoDir.exists()) {
            Files.createDirectories(repoPath);
            // git init --bare
            gitRunner.execute(
                    repoDir,
                    List.of("init", "--bare"),
                    null,
                    null,
                    java.io.OutputStream.nullOutputStream());
            log.info("Initialized quarantine bare repository at {}", repoPath);
        }

        return repoDir;
    }

    /**
     * Creates a unique transaction directory for incoming packfiles or isolation.
     */
    public Path createTransactionWorkspace(String org, String repo) throws IOException {
        String txId = UUID.randomUUID().toString();
        Path txPath = quarantineRoot.resolve("tx").resolve(txId);
        Files.createDirectories(txPath);
        return txPath;
    }

    /**
     * Safely deletes a directory and its contents recursively.
     */
    public void cleanupWorkspace(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walk(directory)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            log.debug("Cleaned up workspace {}", directory);
        } catch (IOException e) {
            log.warn("Failed to cleanly delete workspace {}: {}", directory, e.getMessage());
        }
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Repository or organization name cannot be empty");
        }
        // Allow alphanumeric, underscores, hyphens and dots
        String sanitized = name.replaceAll("[^a-zA-Z0-9._-]", "");
        if (sanitized.contains("..")) {
            throw new IllegalArgumentException("Invalid path traversal sequence in name: " + name);
        }
        return sanitized;
    }
}
