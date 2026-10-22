package com.infornics.sordian.modules.git.service;

import com.infornics.sordian.modules.git.model.RefCommand;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.Value;
import org.springframework.stereotype.Service;

/**
 * Parses and extracts Git pkt-line reference update commands from the push stream.
 */
@Service
public class GitPktLineParser {

    @Value
    public static class ParseResult {
        List<RefCommand> commands;
        byte[] rawPreamble; // Exact raw bytes of commands + 0000 flush packet
        List<String> clientCapabilities;
    }

    /**
     * Reads the initial command lines from the incoming stream until the 0000 flush packet.
     * The remaining bytes in the stream will be the raw packfile data.
     */
    public ParseResult parsePushCommands(InputStream in) throws IOException {
        List<RefCommand> commands = new ArrayList<>();
        List<String> capabilities = new ArrayList<>();
        ByteArrayOutputStream preambleRecorder = new ByteArrayOutputStream();

        while (true) {
            byte[] pkt = readPktLine(in, preambleRecorder);
            if (pkt == null) {
                // 0000 flush packet reached: marks the end of ref commands
                break;
            }

            String line = new String(pkt, StandardCharsets.UTF_8).trim();
            if (line.isEmpty()) {
                continue;
            }

            // The very first line may contain capabilities separated by \0 (null byte)
            // Example: "0000... <new-oid> refs/heads/main\0report-status side-band-64k"
            int nullIdx = line.indexOf('\0');
            String commandPart = line;
            if (nullIdx != -1) {
                commandPart = line.substring(0, nullIdx);
                String capPart = line.substring(nullIdx + 1);
                for (String cap : capPart.split(" ")) {
                    if (!cap.isBlank()) {
                        capabilities.add(cap);
                    }
                }
            }

            String[] parts = commandPart.split(" ");
            if (parts.length >= 3) {
                commands.add(RefCommand.builder()
                        .oldOid(parts[0])
                        .newOid(parts[1])
                        .refName(parts[2])
                        .build());
            }
        }

        return new ParseResult(commands, preambleRecorder.toByteArray(), capabilities);
    }

    private byte[] readPktLine(InputStream in, ByteArrayOutputStream recorder) throws IOException {
        byte[] lenBytes = in.readNBytes(4);
        if (lenBytes.length < 4) {
            throw new EOFException("Premature EOF while reading Git pkt-line length");
        }
        recorder.write(lenBytes);

        String lenHex = new String(lenBytes, StandardCharsets.UTF_8);
        int len;
        try {
            len = Integer.parseInt(lenHex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Git pkt-line length prefix: " + lenHex, e);
        }

        if (len == 0) {
            // "0000" flush packet
            return null;
        }
        if (len < 4) {
            throw new IOException("Corrupted Git pkt-line length: " + len);
        }

        byte[] payload = in.readNBytes(len - 4);
        if (payload.length < (len - 4)) {
            throw new EOFException("Premature EOF reading Git pkt-line payload of len " + len);
        }
        recorder.write(payload);

        return payload;
    }
}
