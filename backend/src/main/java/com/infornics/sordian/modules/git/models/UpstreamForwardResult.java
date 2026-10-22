package com.infornics.sordian.modules.git.models;

import lombok.Builder;
import lombok.Value;

/**
 * Result of forwarding approved quarantine ref updates to upstream GitHub.
 */
@Value
@Builder
public class UpstreamForwardResult {
    boolean success;
    int exitCode;
    String standardOutput;
    String standardError;
    String upstreamUrl;
}
