# 04: Inspection & Policy Guards

This document explains the inspection mechanics and safety invariants enforced by Sordian during push evaluation, specifically detailing branch deletion prevention and history rewrite / force-push rejection.

---

## 1. Safety Invariants Overview

In modern engineering teams, two categories of Git push operations present existential risks to codebase stability:
1. **Accidental Branch Deletions:** Deleting critical branches (e.g. `main`, `production`, `release/*`) instantly breaks continuous integration pipelines and deployments.
2. **History Rewrites (Force-Pushes):** Using `git push --force` or `--force-with-lease` to rewrite shared commit history drops commits, invalidates code reviews, and desynchronizes team members' local repositories.

Sordian guarantees that these destructive operations are intercepted and denied **before** any changes land in the repository.

---

## 2. Invariant 1: Branch Deletion Prevention (FR-10)

### 2.1 Wire Protocol Representation
In the Git Smart HTTP wire protocol, reference updates take the form:
```text
<old-oid> <new-oid> <ref-name>
```

When a developer runs `git push origin --delete <branch>`, the Git client transmits forty hexadecimal zeros as the target object ID:
$$\text{new-oid} = \text{0000000000000000000000000000000000000000}$$

### 2.2 Detection Logic
In `RefCommand.java`, Sordian detects this condition deterministically:
```java
public static final String ZERO_OID = "0000000000000000000000000000000000000000";

public boolean isDelete() {
    return ZERO_OID.equals(newOid);
}
```

### 2.3 Wire Protocol Rejection Packet
When a deletion is detected on a protected branch, `GitInspectionService` immediately rejects the command without unpacking the payload:

```
+-------------------------------------------------------------------------------------------------------------------+
| 0069ng refs/heads/main [SORDIAN REJECTED] Branch deletion is denied by default to prevent history reset.\n        |
| 0000                                                                                                              |
+-------------------------------------------------------------------------------------------------------------------+
```

From the developer's perspective, their terminal displays:
```text
! [remote rejected] main (Branch deletion is denied by default to prevent history reset.)
error: failed to push some refs to 'http://sordian.gateway/acme/core-api.git'
```

---

## 3. Invariant 2: Force-Push & Non-Fast-Forward Rejection (FR-09, FR-15)

### 3.1 What is a Non-Fast-Forward Push?
A push is a **fast-forward** update if and only if the branch's current commit (`old-oid`) is a direct ancestor of the incoming commit (`new-oid`):

```
                   LINEAR FAST-FORWARD UPDATE (PERMITTED)
                   
Old History:    [Commit A] ───> [Commit B (old-oid)]
                                       │
New Push:                              ▼
                                [Commit C] ───> [Commit D (new-oid)]
(Commit B is an ancestor of Commit D; no history is lost)


                   NON-FAST-FORWARD / FORCE-PUSH (REJECTED)
                   
Old History:    [Commit A] ───> [Commit B (old-oid)]
                       │
New Push:              └───> [Commit X] ───> [Commit Y (new-oid)]
(Commit B would be dropped from the branch history! Commits are lost!)
```

### 3.2 Ancestry Verification via `git merge-base`

To verify whether `old-oid` is an ancestor of `new-oid`, Sordian invokes Git's native graph traversal engine:

```bash
git merge-base --is-ancestor <old-oid> <new-oid>
```

- **Exit Code 0:** `old-oid` is an ancestor of `new-oid`. The push is a clean fast-forward.
- **Exit Code 1:** `old-oid` is NOT an ancestor of `new-oid`. The push is attempting to rewrite commit history.
- **Exit Code 128 / Error:** One of the commit hashes does not exist in the repository graph.

### 3.3 New Branch Creation Exception
When creating a brand new branch, there is no previous commit on that reference. The Git client transmits all zeros for `old-oid`:
$$\text{old-oid} = \text{0000000000000000000000000000000000000000}$$

Sordian identifies this via `RefCommand.isCreate()`:
```java
public boolean isCreate() {
    return ZERO_OID.equals(oldOid);
}
```
New branch creations are automatically recognized as non-destructive and permitted to proceed to quarantine unpack.

---

## 4. Rejection Wire Formatting

When a push violates an invariant, Sordian formats the response strictly according to the Git receive-pack wire specification:
1. `unpack ok\n`: Informs the Git client that the transport channel itself is operational.
2. `ng <ref-name> [SORDIAN REJECTED] <reason>\n`: Rejects the specific reference with a human-readable explanation prefixed by `[SORDIAN REJECTED]`.
3. `0000`: Flush packet concluding the report-status conversation.

The Git client automatically surfaces this message directly to the developer's CLI with standard Git coloring.

---

## 5. Next Document
To understand how permitted ref updates are forwarded to upstream GitHub and confirmed back to the client, proceed to [05_Upstream_Forwarding_Engine.md](./05_Upstream_Forwarding_Engine.md).
