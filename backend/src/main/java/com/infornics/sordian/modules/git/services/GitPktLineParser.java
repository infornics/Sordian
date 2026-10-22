package com.infornics.sordian.modules.git.services;

import com.infornics.sordian.modules.git.models.RefCommand;
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

            // Strip literal "\n0000" or "\n" if typed literally in test tools
            if (line.contains("\\n")) {
                line = line.substring(0, line.indexOf("\\n")).trim();
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

            String[] parts = commandPart.trim().split("\\s+");
            if (parts.length >= 3) {
                commands.add(RefCommand.builder()
                        .oldOid(parts[0].trim())
                        .newOid(parts[1].trim())
                        .refName(parts[2].trim())
                        .build());
            }
        }

        return new ParseResult(commands, preambleRecorder.toByteArray(), capabilities);
    }

    private byte[] readPktLine(InputStream in, ByteArrayOutputStream recorder) throws IOException {
        // Read 4 bytes of length prefix
        byte[] lenBytes = new byte[4];
        int read = 0;
        while (read < 4) {
            int b = in.read();
            if (b == -1) {
                if (read == 0) {
                    return null;
                }
                throw new EOFException("Premature EOF while reading Git pkt-line length prefix");
            }
            // Skip leading whitespace / newlines between packets
            if (read == 0 && (b == '\n' || b == '\r')) {
                continue;
            }
            lenBytes[read++] = (byte) b;
        }

        String lenHex = new String(lenBytes, StandardCharsets.UTF_8);

        // Check if length prefix is standard hex
        int len;
        boolean isHex = false;
        try {
            len = Integer.parseInt(lenHex, 16);
            isHex = true;
        } catch (NumberFormatException e) {
            len = -1;
        }

        if (isHex) {
            recorder.write(lenBytes);
            if (len == 0) {
                // "0000" flush packet
                return null;
            }
            if (len < 4) {
                throw new IOException("Corrupted Git pkt-line length: " + len);
            }

            // Read the exact payload specified by hex length
            byte[] payload = in.readNBytes(len - 4);
            if (payload.length < (len - 4)) {
                throw new EOFException("Premature EOF reading Git pkt-line payload of len " + len);
            }
            recorder.write(payload);
            return payload;
        }

        // Fallback for manual test tools (like Postman sending un-prefixed lines or line without exact hex count):
        // Read until newline \n
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        lineBuf.write(lenBytes);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            lineBuf.write(b);
        }
        byte[] lineBytes = lineBuf.toByteArray();
        String lineStr = new String(lineBytes, StandardCharsets.UTF_8).trim();

        if (lineStr.isEmpty() || lineStr.equals("0000")) {
            return null;
        }

        // Reconstruct proper pkt-line into recorder
        int totalLen = lineBytes.length + 5; // +4 for hex prefix + 1 for newline
        recorder.write(String.format("%04x", totalLen).getBytes(StandardCharsets.UTF_8));
        recorder.write(lineBytes);
        recorder.write('\n');

        return lineBytes;
    }
}
