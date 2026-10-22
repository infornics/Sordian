package com.infornics.sordian.modules.git.model;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a single proposed reference update parsed from a Git push packet.
 * Protocol line format: "<old-oid> <new-oid> <ref-name>"
 */
@Value
@Builder
public class RefCommand {

    String oldOid;
    String newOid;
    String refName;

    public static final String ZERO_OID = "0000000000000000000000000000000000000000";

    /**
     * Checks if this command represents a branch or tag deletion (new-oid is all zeros).
     */
    public boolean isDelete() {
        return ZERO_OID.equals(newOid);
    }

    /**
     * Checks if this command represents creating a brand new branch or tag (old-oid is all zeros).
     */
    public boolean isCreate() {
        return ZERO_OID.equals(oldOid);
    }

    /**
     * Checks if this command targets a branch (e.g. refs/heads/main).
     */
    public boolean isBranch() {
        return refName != null && refName.startsWith("refs/heads/");
    }

    /**
     * Checks if this command targets a tag (e.g. refs/tags/v1.0.0).
     */
    public boolean isTag() {
        return refName != null && refName.startsWith("refs/tags/");
    }

    /**
     * Extracts the simple branch name (e.g. "main" from "refs/heads/main").
     */
    public String getBranchName() {
        if (isBranch()) {
            return refName.substring("refs/heads/".length());
        }
        return refName;
    }
}
