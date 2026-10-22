# 03: Quarantine Storage & Subprocess Execution

This document details the disk quarantine storage subsystem and the process isolation runtime used by Sordian to receive, inspect, and execute Git operations safely.

---

## 1. Quarantine Workspace Storage Subsystem (`GitQuarantineService`)

When receiving an untrusted Git push, code should never be written directly to the target production repository. Sordian maintains an isolated filesystem quarantine tier.

### 1.1 Directory Layout & Organization

Quarantine workspaces are allocated within a configurable directory on disk (defaulting to `/tmp/sordian/quarantine`):

```
/tmp/sordian/quarantine/
├── <org-slug>/
│   └── <repo-slug>.git/            # Bare Git repository mirror
│       ├── HEAD
│       ├── config
│       ├── objects/                # Object database (commits, trees, blobs)
│       └── refs/                   # Reference namespace (heads, tags)
└── tx/                             # Isolated transaction scratch workspaces
    └── <uuid>/                     # Ephemeral transaction root (deleted on completion)
```

### 1.2 Path Traversal & Identifier Sanitization

Because the organization (`org`) and repository (`repo`) parameters originate directly from client-controlled URL paths (`/{org}/{repo}.git/...`), they represent potential path traversal vulnerabilities (`../../etc/passwd`).

Sordian enforces strict path sanitization before touching the filesystem:
```java
private String sanitizeName(String name) {
    if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Name cannot be empty");
    }
    // Allow alphanumeric, underscores, hyphens, and dots only
    String sanitized = name.replaceAll("[^a-zA-Z0-9._-]", "");
    if (sanitized.contains("..")) {
        throw new IllegalArgumentException("Path traversal sequence detected: " + name);
    }
    return sanitized;
}
```

### 1.3 Bare Repository Lifecycle
If a bare quarantine repository does not exist for the target repository, Sordian initializes one automatically on demand:
```bash
git init --bare /tmp/sordian/quarantine/<org>/<repo>.git
```
This bare repository holds the unpack target for incoming packfiles and provides the local object store for commit ancestor calculations (`git merge-base`).

---

## 2. Process Execution Runtime (`GitProcessRunner`)

Rather than relying on embedded Java Git libraries (like JGit) which may have compatibility discrepancies with native Git C implementations, Sordian utilizes **native Git CLI execution**. 

To maintain security, high performance, and system stability, `GitProcessRunner` implements three core design invariants:

```
[ GitProcessRunner ]
  ├── 1. Argument Sanitization (List<String>, No Shell Interpolation)
  ├── 2. Java 21 Virtual Threads (Concurrent stdin/stdout/stderr streaming)
  └── 3. POSIX Hard Timeouts (Forcible process destruction after 60s)
```

### 2.1 Preventing Shell Injection
When executing operating system processes, invoking shells (`/bin/sh -c "git ... "`) creates severe command injection vulnerabilities if branch or commit names contain malicious shell metacharacters (`; rm -rf /`, `&&`, `` ` ``).

Sordian passes arguments strictly as discrete tokens using Java's `ProcessBuilder`:
```java
List<String> fullCommand = new ArrayList<>();
fullCommand.add("git");
fullCommand.addAll(commandArgs);

ProcessBuilder pb = new ProcessBuilder(fullCommand);
pb.directory(workingDirectory);
```
No shell interpreter is invoked. Every argument is delivered directly to the operating system's `execve` syscall as an individual string element.

---

### 2.2 Deadlock-Free Stream Piping via Java 21 Virtual Threads

A classic failure mode in server-side process execution is **I/O stream deadlock**:
- If a child process produces significant output to `stderr` while the parent process is blocked waiting to read from `stdout`, the operating system's pipe buffer (typically 64KB) fills up.
- The child process blocks attempting to write to `stderr`.
- The parent process blocks attempting to read from `stdout`.
- The system deadlocks indefinitely.

Sordian eliminates this issue using **Java 21 Virtual Threads**:

```
+-------------------------------------------------------------------------+
| Sordian Gateway (Spring Boot / Java 21)                                 |
|                                                                         |
|  Virtual Thread 1: Stdin Transfer                                       |
|  [ Client Stream ] ──────── transferTo() ────────> [ Child Process In ] |
|                                                                         |
|  Virtual Thread 2: Stderr Transfer                                      |
|  [ Child Process Err ] ──── transferTo() ────────> [ Stderr Buffer ]    |
|                                                                         |
|  Main Virtual Thread: Stdout Transfer                                   |
|  [ Child Process Out ] ──── transferTo() ────────> [ Client Socket ]    |
+-------------------------------------------------------------------------+
```

Because virtual threads are lightweight (requiring just a few kilobytes of RAM compared to ~1MB for platform threads), Sordian can safely spawn concurrent stream pump threads per execution without degrading JVM throughput.

---

### 2.3 Process Execution Timeouts & Environmental Isolation

Every Git process execution is bounded by an explicit timeout (default: 60 seconds):

```java
boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
if (!finished) {
    process.destroyForcibly();
    throw new IOException("Git process timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
}
```

Additionally, subprocesses run with a sanitized environment:
- `GIT_TERMINAL_PROMPT=0` ensures the Git process never stalls waiting for console input.
- `GIT_ASKPASS=echo` guarantees credential queries fail instantly rather than freezing background worker threads.

---

## 3. Next Document
To understand how Sordian evaluates Git safety invariants, prevents branch deletion, and rejects force-pushes before code is unpacked, proceed to [04_Inspection_and_Policy_Guards.md](./04_Inspection_and_Policy_Guards.md).
