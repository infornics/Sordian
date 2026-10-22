package com.infornics.sordian.modules.git.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.infornics.sordian.modules.git.models.RefCommand;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GitPktLineParserTest {

    private final GitPktLineParser parser = new GitPktLineParser();

    @Test
    @DisplayName("Should correctly parse standard ref update command with capabilities and flush packet")
    void shouldParseRefUpdateWithCapabilities() throws IOException {
        String oldSha = "7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b";
        String newSha = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b";
        String line = oldSha + " " + newSha + " refs/heads/feature-login\0report-status side-band-64k\n";

        // Git pkt-line length in hex: 4 bytes length prefix + payload length
        int len = line.getBytes(StandardCharsets.UTF_8).length + 4;
        String hexLen = String.format("%04x", len);

        String streamData = hexLen + line + "0000";
        ByteArrayInputStream in = new ByteArrayInputStream(streamData.getBytes(StandardCharsets.UTF_8));

        GitPktLineParser.ParseResult result = parser.parsePushCommands(in);

        assertThat(result.getCommands()).hasSize(1);
        RefCommand cmd = result.getCommands().get(0);
        assertThat(cmd.getOldOid()).isEqualTo(oldSha);
        assertThat(cmd.getNewOid()).isEqualTo(newSha);
        assertThat(cmd.getRefName()).isEqualTo("refs/heads/feature-login");
        assertThat(cmd.getBranchName()).isEqualTo("feature-login");
        assertThat(cmd.isBranch()).isTrue();
        assertThat(cmd.isDelete()).isFalse();
        assertThat(cmd.isCreate()).isFalse();

        assertThat(result.getClientCapabilities()).contains("report-status", "side-band-64k");
    }

    @Test
    @DisplayName("Should detect branch deletion command when new-oid is all zeros")
    void shouldDetectBranchDeletion() throws IOException {
        String oldSha = "7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b";
        String newSha = RefCommand.ZERO_OID;
        String line = oldSha + " " + newSha + " refs/heads/old-experiment\n";

        int len = line.getBytes(StandardCharsets.UTF_8).length + 4;
        String hexLen = String.format("%04x", len);

        String streamData = hexLen + line + "0000";
        ByteArrayInputStream in = new ByteArrayInputStream(streamData.getBytes(StandardCharsets.UTF_8));

        GitPktLineParser.ParseResult result = parser.parsePushCommands(in);

        assertThat(result.getCommands()).hasSize(1);
        RefCommand cmd = result.getCommands().get(0);
        assertThat(cmd.isDelete()).isTrue();
        assertThat(cmd.getBranchName()).isEqualTo("old-experiment");
    }
}
