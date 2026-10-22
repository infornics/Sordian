package com.infornics.sordian.modules.git.services;

import com.infornics.sordian.modules.git.models.RefCommand;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Inspects Git commit history to verify fast-forward status and prevent history rewrites.
 */
@Service
@RequiredArgsConstructor
public class GitInspectionService {

    private static final Logger log = LoggerFactory.getLogger(GitInspectionService.class);

    private final GitProcessRunner gitRunner;

    /**
     * Determines whether an update from oldOid to newOid is a fast-forward update.
     * Uses: git merge-base --is-ancestor <old-oid> <new-oid>
     * - Returns true if oldOid is an ancestor of newOid (fast-forward) or if it's a new branch creation.
     * - Returns false if oldOid is not an ancestor of newOid (force-push / history rewrite).
     */
    public boolean isFastForward(File repoDir, RefCommand command) {
        if (command.isCreate()) {
            return true;
        }

        if (command.isDelete()) {
            return false;
        }

        try {
            List<String> args = List.of(
                    "merge-base",
                    "--is-ancestor",
                    command.getOldOid(),
                    command.getNewOid()
            );

            int exitCode = gitRunner.execute(
                    repoDir,
                    args,
                    null,
                    null,
                    OutputStream.nullOutputStream()
            );

            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to inspect fast-forward status for ref {} ({} -> {}): {}",
                    command.getRefName(), command.getOldOid(), command.getNewOid(), e.getMessage());
            return false;
        }
    }

    /**
     * Inspects a list of proposed ref updates against Git invariants:
     * 1. Reject branch deletions (FR-10).
     * 2. Reject non-fast-forward updates / force-pushes (FR-09 & FR-15).
     */
    public com.infornics.sordian.modules.git.models.PushValidationResult validatePushCommands(
            File repoDir,
            List<RefCommand> commands) {

        for (RefCommand command : commands) {
            if (command.isDelete()) {
                log.warn("Rejected branch deletion attempt on ref {}", command.getRefName());
                return com.infornics.sordian.modules.git.models.PushValidationResult.reject(
                        "Branch deletion is denied by default to prevent history reset.",
                        command
                );
            }

            if (!isFastForward(repoDir, command)) {
                log.warn("Rejected non-fast-forward update on ref {} ({} -> {})",
                        command.getRefName(), command.getOldOid(), command.getNewOid());
                return com.infornics.sordian.modules.git.models.PushValidationResult.reject(
                        "Non-fast-forward updates are strictly prohibited. Rewriting history is forbidden.",
                        command
                );
            }
        }

        return com.infornics.sordian.modules.git.models.PushValidationResult.allow();
    }
}
