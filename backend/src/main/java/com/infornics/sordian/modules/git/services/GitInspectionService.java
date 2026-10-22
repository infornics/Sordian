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
        // New branch creation (oldOid is all zeros) is always allowed as fast-forward
        if (command.isCreate()) {
            return true;
        }

        // Branch deletion (newOid is all zeros) is handled separately
        if (command.isDelete()) {
            return false;
        }

        try {
            // git merge-base --is-ancestor <old-oid> <new-oid>
            // Exit code 0 means old-oid is an ancestor of new-oid (Fast-Forward: PASS)
            // Exit code 1 means old-oid is NOT an ancestor (Force-push / Diverged: REJECT)
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
}
