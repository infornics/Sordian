# 05: Upstream Forwarding Engine

This document details how Sordian validates quarantine unpack status, authenticates against upstream GitHub, forwards approved objects, and provides atomic completion acknowledgments to the developer.

---

## 1. Upstream Forwarding Architecture

Once a push transaction has passed all pre-unpack invariant checks, Sordian's `GitUpstreamForwarderService` is responsible for bridging the local quarantine repository with the authoritative GitHub repository.

```
+-------------------------------------------------------------------------------------------------------+
| Sordian Gateway                                                                                       |
|                                                                                                       |
|  1. Quarantine Unpack                                                                                 |
|  [ Reconstituted Stream ] ──> git receive-pack --stateless-rpc ──> [ Quarantine Bare Repository ]     |
|                                                                                    │                  |
|  2. Upstream Forwarding                                                            ▼                  |
|  [ Upstream Forwarder ] <── git push <token-url> <new-oid>:<ref> <────────────────┘                  |
|            │                                                                                          |
+------------┼------------------------------------------------------------------------------------------+
             │ HTTPS (TLS 1.3)
             ▼
+------------------------------------+
| GitHub Upstream (github.com)       |
| Authoritative Source of Truth      |
+------------------------------------+
```

---

## 2. Authentication Mechanics

Sordian authenticates against GitHub using standard GitHub App installation tokens or configured Personal Access Tokens (PAT).

### 2.1 Credential URL Construction
Rather than writing credentials to a `.gitconfig` or `.git-credentials` file on disk (which creates security risks in shared hosting environments), credentials are provided per transaction using Git's standard `x-access-token` basic auth URL pattern:

```java
private String resolveUpstreamUrl(String org, String repo) {
    if (upstreamToken != null && !upstreamToken.isBlank()) {
        // Format: https://x-access-token:<TOKEN>@github.com/{org}/{repo}.git
        return String.format("https://x-access-token:%s@github.com/%s/%s.git", upstreamToken, org, repo);
    }
    return String.format("%s/%s/%s.git", upstreamBaseUrl, org, repo);
}
```

### 2.2 Preventing Interactive Terminal Prompts
If an upstream token is invalid or expired, Git CLI defaults to pausing execution and prompting for interactive username/password entry on stdin. In a server daemon, this would permanently block the executing thread.

To prevent this, Sordian injects explicit environment variables:
```java
Map<String, String> env = Map.of(
    "GIT_TERMINAL_PROMPT", "0",
    "GIT_ASKPASS", "echo"
);
```
- `GIT_TERMINAL_PROMPT=0`: Disables console credential prompts.
- `GIT_ASKPASS=echo`: Directs Git to invoke a dummy echo script that returns empty input on any unexpected authentication query, causing Git to fail fast immediately.

---

## 3. Explicit Refspec Targeting

When forwarding objects from the quarantine repository to upstream GitHub, Sordian does not issue a blind `git push --all` or `git push --mirror`. Doing so could accidentally synchronize unapproved branches or internal quarantine refs.

Instead, Sordian constructs explicit, fully qualified refspecs:
$$\text{refspec} = \langle\text{new-oid}\rangle : \langle\text{ref-name}\rangle$$

For example, when pushing commit `c7af03b` to branch `test-feature`:
```bash
git push https://x-access-token:***@github.com/org/repo.git c7af03b879e961956555ddf0f622d64a259c2777:refs/heads/test-feature
```
This guarantees that **only the specific approved commit** is pushed to the exact requested branch name on GitHub.

---

## 4. Atomic Status Reporting

Sordian guarantees **atomic status reporting**: the developer's Git client never receives an `ok` receipt until GitHub has accepted the objects.

### 4.1 Success Scenario
If GitHub responds with exit code `0`:
1. Sordian constructs the success response:
   ```text
   000eunpack ok\n
   0023ok refs/heads/test-feature\n
   0000
   ```
2. If the client negotiated `side-band-64k`, the response is encapsulated in Band 1 (`0x01`).
3. The developer's terminal reports:
   ```text
   To http://sordian.gateway/org/repo.git
    * [new branch]      test-feature -> test-feature
   ```

### 4.2 Upstream Failure Scenario
If GitHub denies the push (e.g. invalid permissions, organization token expiration, or GitHub-side protection rules):
1. Sordian captures `stderr` from the upstream Git subprocess.
2. Sordian returns wire packet:
   ```text
   000eunpack ok\n
   0057ng refs/heads/test-feature [SORDIAN UPSTREAM ERROR] <stderr-details>\n
   0000
   ```
3. The developer is informed immediately of the exact failure reason without leaving the local repository in an inconsistent state.

---

## 5. Next Document
To run automated test suites, verify live pushes using the Git CLI, and test API endpoints using Postman, proceed to [06_Testing_and_Verification_Playbook.md](./06_Testing_and_Verification_Playbook.md).
