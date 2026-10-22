# 02: Smart HTTP & Wire Protocol

This document details the low-level network protocol, framing standards, HTTP interactions, and multiplexing mechanics implemented in Sordian's Git Smart HTTP transport layer.

---

## 1. Git Smart HTTP Overview

Git Smart HTTP (originally defined in the Git transfer protocols and RFC 5245 standards) allows standard Git clients to communicate with remote repositories over standard HTTP/HTTPS infrastructure (ports 80 and 443).

### 1.1 Service Endpoints

Sordian exposes two primary Smart HTTP endpoints per repository:

| HTTP Method | URL Path Pattern | Purpose |
| :--- | :--- | :--- |
| `GET` | `/{org}/{repo}.git/info/refs?service=git-receive-pack` | **Handshake & Ref Advertisement:** Informs the Git client of current remote branch pointers and supported capabilities. |
| `POST` | `/{org}/{repo}.git/git-receive-pack` | **Push Execution:** Receives client ref update commands and raw zlib-compressed packfile streams. |

### 1.2 Write-Only Proxy Policy (Blocking Read Operations)

A core design decision in Sordian is that **only write operations (`git-receive-pack`) are proxied**.

When a developer performs a read operation (e.g., `git clone` or `git fetch`), the Git client invokes the service `git-upload-pack`. Sordian immediately blocks this request with **HTTP 403 Forbidden**:

```
Client GET /{org}/{repo}.git/info/refs?service=git-upload-pack
                       │
                       ▼
Sordian Check: service == "git-receive-pack"?
                       │
                 No (upload-pack)
                       │
                       ▼
HTTP 403 Forbidden: "Sordian only proxies Git pushes ('git-receive-pack'). Please fetch/clone directly from GitHub."
```

#### Why Block Read Operations?
1. **Network & I/O Conservation:** Cloning a massive multi-gigabyte repository through the governance proxy would tie up network sockets and disk cache on the Sordian gateway.
2. **Zero Read Latency:** Developers clone and fetch directly from GitHub's global edge CDN, guaranteeing maximal download performance.
3. **Governance Focus:** Sordian's mission is strict write-path interception; read operations carry zero governance risk.

---

## 2. Pkt-Line Framing Specification

The Git wire protocol communicates using variable-length packets called **pkt-lines** (packet-lines).

### 2.1 Pkt-Line Structure

Every packet begins with a **4-character hexadecimal length prefix**, followed by the payload. The length includes the 4 bytes of the prefix itself.

```
+--------------------------------------------------------------+
| 4-Hex Bytes Length | Payload Data (UTF-8 or Binary)          |
+--------------------------------------------------------------+
```

#### Examples:
- **Length `000a`:** Total length is 10 bytes ($10_{10} = \text{0x000a}$). Prefix (4 bytes) + Payload (6 bytes):
  ```text
  000afoobar
  ```
- **Length `0014`:** Total length is 20 bytes ($20_{10} = \text{0x0014}$). Prefix (4 bytes) + Payload (16 bytes):
  ```text
  0014unpack ok\n
  ```
- **Special Length `0000` (PKT-FLUSH):** Represents a flush packet, signifying the end of a message section or ref command list.

---

## 3. Preamble & Capability Extraction

When a developer runs `git push`, the POST request body sends commands followed by the raw packfile:

```
005e0000000000000000000000000000000000000000 2222...2222 refs/heads/feature\0report-status side-band-64k agent=git/2.43.0\n
0000
[RAW BINARY PACKFILE DATA...]
```

### 3.1 Preamble Anatomy
1. **Old Object ID (40 hex chars):** The commit hash the client expects the branch to be at currently (or `0000000000000000000000000000000000000000` if creating a brand new branch).
2. **New Object ID (40 hex chars):** The target commit hash the client wishes to advance the branch to (or all zeros if deleting).
3. **Reference Name:** The fully qualified ref path (e.g. `refs/heads/main`).
4. **Capabilities String:** Appended to the very first command line following a null byte (`\0`):
   - `report-status`: Requests explicit status reports per ref (`ok` or `ng`).
   - `side-band-64k`: Demands multi-channel streaming multiplexing.
   - `agent=...`: Client software identifier.
5. **Flush Packet (`0000`):** Demarcates the end of command lines and the exact starting byte of the binary Git packfile.

### 3.2 Non-Destructive Stream Parsing
Because the HTTP request stream delivers both text headers and binary object data over a single TCP socket, Sordian's `GitPktLineParser` reads strictly byte-by-byte up to the `0000` flush packet. It stores the consumed command bytes in an internal buffer (`rawPreamble`) while leaving the underlying stream positioned precisely at the first byte of the binary packfile.

---

## 4. Sideband Multiplexing Protocol (`side-band-64k`)

When Git pushes large commits, it does not send raw plain text; it multiplexes stdout, progress indicators, and errors using the **Sideband Protocol**.

### 4.1 Sideband Channels

Each packet transmitted over sideband has an additional 1-byte channel identifier immediately following the 4-hex length prefix:

```
+--------------------+--------------+------------------------------------+
| 4-Hex Length (len) | Channel Byte | Payload Data                       |
+--------------------+--------------+------------------------------------+
| len (includes +1)  | 0x01, 2, or 3| Status, Progress, or Error text   |
+--------------------+--------------+------------------------------------+
```

- **Channel 1 (`0x01`) — Data/Status Band:** Carries the primary Git wire response (e.g., `unpack ok`, `ok refs/heads/...`).
- **Channel 2 (`0x02`) — Progress Band:** Carries progress percentages printed to the developer's console (`remote: Compressing objects: 100%`).
- **Channel 3 (`0x03`) — Error Band:** Carries critical server failure alerts, rendered in red on the developer's terminal (`remote: fatal: ...`).

### 4.2 The `bad band #117` Protocol Bug & Its Resolution

During early protocol implementation, returning status lines without channel encapsulation caused the Git CLI to fail with:
```text
send-pack: protocol error: bad band #117
```

#### Why Did This Happen?
When a Git client advertises `side-band-64k`, it expects the byte immediately following the length prefix to be the channel ID (`0x01`, `0x02`, or `0x03`).
- If Sordian sent `000eunpack ok\n`, Git read the first byte of the payload: the ASCII character `'u'`.
- In ASCII, `'u'` has the decimal value **117** (`0x75`).
- Git attempted to route the message to Channel 117, discovered no such channel exists, and crashed.

#### The Architectural Fix
Sordian checks if `side-band-64k` or `side-band` was requested in client capabilities. If true, it dynamically prefixes Channel 1 (`0x01`) and adjusts the hex length header by +1:

```java
private void writeStatusLine(OutputStream out, String text, boolean sideband) throws IOException {
    byte[] payload = text.getBytes(StandardCharsets.UTF_8);
    if (sideband) {
        // [4-byte hex prefix][0x01 band byte][payload]
        int len = 4 + 1 + payload.length;
        String hexLen = String.format("%04x", len);
        out.write(hexLen.getBytes(StandardCharsets.UTF_8));
        out.write(1); // Band #1
        out.write(payload);
    } else {
        writePktLine(out, text);
    }
}
```

---

## 5. Next Document
To understand how Sordian manages local disk workspaces and executes native Git CLI binaries without process deadlocks, proceed to [03_Quarantine_Storage_and_Subprocess.md](./03_Quarantine_Storage_and_Subprocess.md).
