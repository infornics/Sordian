package com.infornics.sordian.modules.git.models;

import lombok.Builder;
import lombok.Value;

/**
 * Result of validating a proposed push against Git history, fast-forward invariants, and branch rules.
 */
@Value
@Builder
public class PushValidationResult {

    boolean allowed;
    String rejectionReason;
    RefCommand rejectedCommand;

    public static PushValidationResult allow() {
        return PushValidationResult.builder()
                .allowed(true)
                .build();
    }

    public static PushValidationResult reject(String reason, RefCommand command) {
        return PushValidationResult.builder()
                .allowed(false)
                .rejectionReason(reason)
                .rejectedCommand(command)
                .build();
    }
}
