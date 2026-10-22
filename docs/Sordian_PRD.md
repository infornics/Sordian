**PRODUCT REQUIREMENTS DOCUMENT | DRAFT**

# **Sordian**

### **Product Requirements Document**

| **Document field** | **Value**                                                                                         |
| ------------------ | ------------------------------------------------------------------------------------------------- |
| Status             | Draft for product and architecture validation                                                     |
| Version            | 0.1                                                                                               |
| Date               | 18 October 2026                                                                                   |
| Product scope      | Private GitHub repositories in an organization; public<br>repositories are not the primary target |

## **1. Product Summary**

Sordian is a policy-enforcement layer for GitHub repositories. It provides centrally configured rules for Git pushes and pull request merges while keeping GitHub as the canonical repository and final destination for accepted code.

Developers continue to use their normal local Git workflow. Fetching and cloning can remain directed at GitHub, while pushes are routed through the gateway. The gateway receives the Git objects needed for the push, evaluates the proposed ref updates, and forwards permitted updates to GitHub. The push command returns success only after the upstream result is confirmed.

The first version targets three controls: block direct pushes to protected branches, reject non-fast-forward branch updates to prevent history rewrites, and prevent pull request merges until the configured approval requirements are met.

## **2. Problem Statement**

The organization wants branch governance for private GitHub repositories without depending on paid GitHub organization features. Developers should not be able to bypass the rules by using the Git command line or another Git client. GitHub must remain the canonical source of repository code; the platform is not intended to replace GitHub with a separate permanent Git host.

A GitHub App can authenticate to GitHub and perform permitted API or Git operations, but it does not independently intercept every direct Git push. Server-side push enforcement therefore requires all ordinary write operations to pass through a controlled gateway and requires direct write access to canonical repositories to be removed from regular developer identities.

## **3. Goals and Non-Goals**

### **3.1 Goals**

- Apply centrally managed, repository-specific rules to private repositories in a GitHub organization.

- Reject direct pushes to configured protected branches.

- Reject non-fast-forward updates to every branch, regardless of whether the user used --force.

- Require the configured number of eligible pull request approvals before a merge is allowed.

- Keep GitHub as the canonical store of accepted branches, commits, and pull requests.

- Keep accepted and rejected push outcomes visible and unambiguous to the developer.

- Record governance decisions and administrative changes for audit and troubleshooting.

### **3.2 Non-Goals for the MVP**

- Replacing GitHub repository hosting, issues, pull requests, review UI, or release management.

- Providing a general-purpose CI/CD system, code scanning suite, or source-code quality platform.

- Replicating every GitHub branch protection or organization ruleset feature.

- Guaranteeing that a GitHub organization owner cannot remove or bypass the platform. Privileged-owner changes must be detectable and documented.

Sordian • Version 0.1 • 1

**PRODUCT REQUIREMENTS DOCUMENT | DRAFT**

- Supporting multiple Git hosting providers in the first release.

- Maintaining a second permanent source-of-truth repository.

## **4. Target Users and Roles**

| **Role**                    | **Responsibilities**                                                                                                 | **MVP permissions**                                                                                   |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Organization administrator  | Connects a GitHub organization, selects<br>repositories, assigns platform admins, and<br>reviews integration health. | Manage organization connection and<br>repository enrollment; manage platform<br>access.               |
| Policy administrator        | Defines protected branches, approval<br>requirements, and policy settings.                                           | Create, edit, publish, and disable policies; view<br>audit records.                                   |
| Developer                   | Clones or fetches from GitHub, pushes<br>changes through the gateway, and opens or<br>updates pull requests.         | Push permitted updates through the gateway;<br>cannot write directly to canonical repositories.       |
| Reviewer                    | Reviews pull requests and submits approvals<br>or change requests in GitHub.                                         | Review pull requests according to repository<br>access; approval eligibility is checked by<br>policy. |
| Governance service identity | Forwards approved branch updates and<br>performs permitted pull request merges.                                      | Limited to explicitly enrolled repositories and<br>required write operations.                         |

## **5. Core Policies**

| **Policy**            | **Required behavior**                                                                                                  | **Clarification**                                                                                                                                                           |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Protected branches    | Reject developer-initiated pushes that target<br>any branch matching a protected pattern.                              | Protected patterns can include exact names<br>such as main or globs such as release/\*; a<br>service-authorized PR merge is handled<br>separately.                          |
| No history rewrites   | Reject any branch update that is not a fast-<br>forward update.                                                        | This is the enforceable definition of blocking<br>force-pushes. Git cannot reliably tell<br>whether --force was typed when the<br>resulting update is still a fast-forward. |
| Required PR approvals | Do not merge until the configured number of<br>eligible approvals is present and valid for<br>the current PR revision. | The PR author cannot satisfy their own<br>approval requirement. Approval freshness<br>and reviewer eligibility must be evaluated by<br>policy.                              |

**Safety rule:** the MVP must prevent branch deletion from being used as a way to reset branch history. Branch deletion should be denied by default through the gateway unless a future explicit policy grants it.

## **6. User Workflows**

### **6.1 Organization and Repository Setup**

**1.** An administrator installs the platform’s GitHub App on the organization and grants access only to the repositories selected for governance.

**2.** The platform discovers the selected repositories and records their identifiers and default branches.

**3.** A policy administrator defines protected branch patterns, approval counts, reviewer eligibility, and branch-update behavior for each repository or policy group.

**4.** The platform verifies that enrolled repositories are configured for controlled writes: ordinary developers have read access to the canonical repository, while the governance service has only the permissions needed to forward updates and merge approved PRs.

**5.** The platform provides repository setup instructions that direct fetches and clones to GitHub while configuring pushes to use the gateway.

### **6.2 Developer Push**

**1.** The developer creates commits locally and runs the normal git push command.

Sordian • Version 0.1 • 2

**PRODUCT REQUIREMENTS DOCUMENT | DRAFT**

**2.** The Git client sends the required objects and proposed ref updates to the gateway. The developer’s working tree remains local; the objects required to evaluate and forward the push are temporarily present on the gateway.

**3.** The gateway authenticates the developer, identifies the repository and proposed ref updates, and evaluates the applicable policy.

**4.** If any requested update violates policy, the gateway rejects the push, does not forward the rejected ref update to GitHub, and returns a specific reason.

**5.** If the update passes policy, the gateway forwards it to GitHub using the controlled service identity. It reports success only after confirming the destination update.

**6.** The gateway deletes temporary data after a definitive outcome and safe cleanup. If the upstream outcome is uncertain, it retains the minimum recovery state required to reconcile or retry safely.

### **6.3 Pull Request Merge**

**1.** A developer pushes a feature branch through the gateway and opens or updates a pull request against the canonical repository.

**2.** The platform reads the pull request state and review data from GitHub and evaluates the configured approval policy.

**3.** If approvals or other configured merge prerequisites are missing, the platform marks the PR as not eligible for merge and explains why.

**4.** When the policy passes, the governance service rechecks the PR head and required conditions, then performs the merge through GitHub.

**5.** The platform records the decision and resulting merge commit. Any change to the PR head or review state that invalidates the decision requires reevaluation.

## **7. Functional Requirements**

### **7.1 GitHub Organization and Repository Management**

- FR-01: The platform MUST connect to a GitHub organization through an installed GitHub App.

- FR-02: An administrator MUST be able to select which repositories are governed.

- FR-03: The platform MUST store a stable repository identifier and must not rely on repository name alone.

- FR-04: The platform MUST show connection state, granted permissions, last successful synchronization, and detected configuration drift.

- FR-05: Removing a repository from governance MUST trigger a warning that the platform will no longer enforce its custom rules for that repository.

### **7.2 Policy Configuration**

- FR-06: A policy administrator MUST be able to define one or more protected branch patterns per repository.

- FR-07: A policy administrator MUST be able to set the minimum number of eligible approvals required to merge a PR.

- FR-08: The policy MUST define whether the PR author can approve; the MVP default MUST prohibit author selfapproval.

- FR-09: The policy MUST reject non-fast-forward updates on all branches, not only protected branches.

- FR-10: The policy MUST deny branch deletion by default to prevent delete-and-recreate from bypassing history protection.

- FR-11: Policy changes MUST take effect only after they are saved and published. The platform MUST record the actor, timestamp, and before/after values.

### **7.3 Push Gateway**

- FR-12: The gateway MUST authenticate every push request and resolve the user, organization, repository, and requested ref updates.

- FR-13: The gateway MUST receive the Git objects required for validation into isolated temporary or quarantined storage before forwarding the proposed ref updates.

- FR-14: The gateway MUST reject developer-initiated updates to protected branch patterns.

- FR-15: The gateway MUST reject every non-fast-forward branch update, including updates attempted with --force.

- FR-16: The gateway MUST NOT report success until GitHub confirms that the requested permitted update has been applied.

- FR-17: The gateway MUST return a clear reason for policy rejection and a distinct response for upstream failure or uncertain outcome.

Sordian • Version 0.1 • 3

**PRODUCT REQUIREMENTS DOCUMENT | DRAFT**

- FR-18: The gateway MUST avoid partial application of multi-ref pushes. The MVP MUST either process such requests safely as a unit or reject unsupported multi-ref pushes before forwarding any ref update.

- FR-19: The gateway MUST revalidate against the latest destination branch state immediately before forwarding and handle concurrent pushes without silently overwriting a newer update.

- FR-20: The gateway MUST clean up temporary objects after rejection or a confirmed successful forward, subject to the recovery requirements in FR-21.

- FR-21: If GitHub’s response is lost or the outcome is uncertain, the gateway MUST retain enough state to reconcile the destination before retrying or reporting a final result.

### **7.4 Pull Request Approval and Merge**

- FR-22: The platform MUST identify pull requests associated with governed repositories and evaluate them against the applicable policy.

- FR-23: The platform MUST count only approvals from eligible reviewers and MUST exclude the PR author when self-approval is prohibited.

- FR-24: The platform MUST invalidate or reevaluate an approval decision when the PR head changes or when configured approval-freshness rules are no longer satisfied.

- FR-25: The platform MUST refuse to merge a PR when required approvals are missing, stale, or from ineligible reviewers.

- FR-26: The governance service MUST recheck the PR head and policy prerequisites immediately before merging to reduce time-of-check/time-of-use errors.

- FR-27: The platform MUST record the merge actor as the governance service and retain the initiating user and policy decision in its own audit record.

### **7.5 Audit and Status**

- FR-28: The platform MUST log successful and rejected pushes, policy violations, merge decisions, policy edits, integration permission changes, and repository enrollment changes.

- FR-29: Each push record MUST include the authenticated actor, repository, target ref, old and proposed commit IDs where available, decision, reason, policy version, and timestamps.

- FR-30: Audit records MUST NOT contain source file contents, GitHub App private keys, access tokens, or other secrets.

- FR-31: Administrators MUST be able to filter audit records by repository, actor, outcome, event type, and date range.

- FR-32: The platform MUST alert administrators when the GitHub App is removed, its permissions change, or a repository’s controlled-write assumptions appear to have changed.

## **8. Temporary Code Handling and Push Response**

Server-side enforcement requires the gateway to receive the Git objects needed to evaluate a push. The developer’s working directory and local repository remain unchanged by the gateway, but the code being pushed is not exclusively local during validation.

| **Push state**    | **Required behavior**                                                                                                                 |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Receiving         | Receive the objects needed for the proposed update into isolated<br>temporary storage. Do not update the canonical GitHub branch.     |
| Validating        | Evaluate identity, target refs, protected branch patterns, fast-<br>forward status, and relevant repository policy.                   |
| Forwarding        | Send permitted updates to GitHub and wait for the upstream result.                                                                    |
| Accepted          | Return success only after the destination update has been<br>confirmed.                                                               |
| Rejected          | Return failure with a specific policy reason. Clean up temporary<br>objects when safe.                                                |
| Outcome uncertain | Do not report success or blindly retry. Reconcile the destination<br>state and retain minimal temporary recovery data until resolved. |

A developer should see the ordinary git push command remain active while the gateway processes the request. The response must be bounded by defined timeouts and must not remain in an indefinite loading state. Large pushes and upstream delays must produce progress or a clear failure/retry result where feasible.

Sordian • Version 0.1 • 4

**PRODUCT REQUIREMENTS DOCUMENT | DRAFT**

## **9. Security and Trust Requirements**

- SEC-01: Regular developers MUST NOT have direct write permission to governed canonical repositories. Their ordinary write path MUST be the gateway.

- SEC-02: The governance service identity MUST be scoped to selected repositories and minimum required permissions.

- SEC-03: The gateway MUST authenticate the human actor separately from the GitHub service identity used to forward updates.

- SEC-04: Secrets and GitHub App credentials MUST be stored securely, never sent to clients, and excluded from logs.

- SEC-05: Authorization MUST be checked on every push and merge operation; client-supplied repository names, usernames, or role claims MUST NOT be trusted without server-side verification.

- SEC-06: Administrative policy edits and changes to integration access MUST be auditable.

- SEC-07: The service MUST fail closed for protected writes when policy evaluation is unavailable. It MUST NOT fall back to direct GitHub writes.

- SEC-08: Webhook events MUST be authenticated, deduplicated, and processed safely when duplicated or delivered out of order.

- SEC-09: The platform MUST detect and report changes that undermine controlled writes, including loss of app access or newly granted direct write access where detectable.

Trust boundary: a GitHub organization owner can potentially remove the app, change repository permissions, or grant direct access. Without native GitHub enforcement, the platform cannot guarantee prevention of every action by a privileged organization owner. The MVP MUST state this limitation clearly and provide detection and alerts; it must not claim tamper-proof enforcement against organization owners.

## **10. Failure Handling and Consistency**

| **Scenario**                                      | **Required behavior**                                                                                                                       |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Gateway unavailable before receiving the push     | The Git push fails because the configured push remote is unavailable.<br>The developer can retry when service is restored.                  |
| Policy service unavailable                        | Reject the push rather than permit it without evaluation.                                                                                   |
| GitHub unavailable before forwarding              | Do not report success. Keep the request pending only within a bounded<br>period, then return an explicit failure or retry-required outcome. |
| GitHub applies an update but the response is lost | Reconcile the target ref with GitHub before retrying to prevent duplicate<br>or conflicting operations.                                     |
| Another push updates the branch during validation | Re-read the destination ref and reject or retry if the proposed update is<br>no longer valid. Never overwrite a newer update silently.      |
| Multi-ref request cannot be handled atomically    | Reject the request before forwarding any ref update and tell the<br>developer how to retry with supported individual updates.               |
| App permissions are revoked                       | Stop forwarding and merging; alert administrators. Do not route around<br>the revoked permission.                                           |

## **11. Non-Functional Requirements**

- Reliability: A push must have an unambiguous final state: accepted, rejected, or explicitly requiring reconciliation. No uncertain outcome may be reported as success.

- Consistency: Policy evaluation must use a versioned policy snapshot. The gateway must check the latest destination state before forwarding and avoid silent ref overwrites.

- Security: Temporary repository data must be isolated by request and repository, access-controlled, and removed according to the retention rules.

- Observability: Operators must be able to trace a push from receipt through policy decision, upstream forwarding, and final response using a correlation ID.

- Usability: Developers must receive actionable rejection messages that identify the rule and target ref without disclosing secrets or sensitive policy internals.

- Performance: Policy checks should add minimal overhead for ordinary pushes. Initial numeric latency and throughput targets will be set after a working prototype and representative push-size testing.

- Recoverability: Interrupted forwarding operations must be reconcilable after service restart without duplicating or silently losing accepted ref updates.

Sordian • Version 0.1 • 5

**PRODUCT REQUIREMENTS DOCUMENT | DRAFT**

## **12. MVP Scope and Exclusions**

### **12.1 Included in MVP**

- One GitHub organization connection with explicit repository enrollment.

- Per-repository protected branch patterns.

- Gateway-mediated pushes with protected-branch rejection.

- Non-fast-forward rejection on all branches and safe handling of branch deletions.

- Configurable required approval count and author self-approval prohibition.

- Controlled PR merge through the governance service.

- Temporary Git object storage, cleanup, retry/reconciliation, and clear push outcomes.

- Repository setup instructions and audit logs for pushes, merges, policies, and integration health.

### **12.2 Excluded from MVP**

- Support for GitLab, Bitbucket, self-hosted Git platforms, or non-GitHub providers.

- Advanced code scanning, secret scanning, dependency scanning, CI execution, deployment automation, or issue/project management.

- A permanent repository mirror or replacement hosting UI.

- Complex enterprise policy inheritance, cross-organization administration, billing, and multi-tenant SaaS onboarding.

- A guarantee that privileged GitHub organization owners cannot disable or bypass the platform.

## **13. Acceptance Criteria**

**1.** A developer can clone or fetch a governed repository from GitHub and push through the configured gateway using the standard Git command.

**2.** A push targeting a protected branch is rejected and the canonical GitHub branch remains unchanged.

**3.** A fast-forward push to an unprotected branch is forwarded and reports success only after GitHub confirms it.

**4.** A non-fast-forward update to any branch is rejected, whether or not the client used --force.

**5.** Branch deletion and recreation cannot be used to bypass history-rewrite protection under the default policy.

**6.** A pull request with fewer than the configured approvals cannot be merged by the governance service.

**7.** A PR author’s own approval does not satisfy the approval requirement when self-approval is prohibited.

**8.** A change to the PR head after approval forces reevaluation according to the configured freshness rule.

**9.** Regular developer identities cannot push directly to canonical GitHub repositories or merge PRs around the service.

**10.** If GitHub is unavailable or its response is uncertain, the platform never reports a successful push without reconciling the final ref state.

**11.** Rejected pushes do not update canonical refs and temporary data is cleaned up when safe.

**12.** Multi-ref pushes are either processed without partial application or rejected before any requested ref is forwarded.

**13.** Administrators can inspect the actor, target ref, commit IDs, policy version, decision, reason, and timestamps for each recorded push.

**14.** Removal of the app or detectable changes to relevant permissions generate an administrator-visible alert.

**15.** Documentation explicitly states that privileged GitHub organization owners may be able to change the trust boundary unless native GitHub controls prevent it.

## **14. Key Assumptions and Open Decisions**

| **Item**             | **Current assumption or decision needed**                                                                                                                                  |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Canonical repository | GitHub remains the only canonical source of truth. The gateway is not a<br>permanent mirror.                                                                               |
| Developer access     | Developers have read access to canonical repositories and use the<br>gateway for pushes. The service identity performs approved writes.                                    |
| Temporary storage    | Objects required for a push are stored temporarily at the gateway during<br>validation and forwarding. Retention and cleanup windows must be<br>defined before production. |

Sordian • Version 0.1 • 6

**PRODUCT REQUIREMENTS DOCUMENT | DRAFT**

| **Item**                  | **Current assumption or decision needed**                                                                                                                             |
| ------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Protected branch behavior | Direct developer pushes are prohibited on protected patterns. The<br>governance service may merge a compliant PR through a separately<br>authorized path.             |
| Force-push semantics      | The platform rejects all non-fast-forward updates. A fast-forward update<br>cannot be identified as an attempted --force from the resulting ref<br>update alone.      |
| Approval rules            | The minimum count is configurable. Eligibility, freshness, team<br>membership changes, and treatment of dismissed reviews must be<br>finalized before implementation. |
| Organization owner bypass | The product detects and reports governance-breaking administrative<br>changes where possible but does not claim absolute enforcement<br>against privileged owners.    |
| Multi-ref pushes          | The implementation must choose between safe atomic handling and<br>explicit rejection of unsupported multi-ref pushes; partial application is<br>not acceptable.      |
| MVP latency and capacity  | Set numeric latency, maximum push size, concurrency, and retention<br>targets after prototype testing with representative repositories.                               |

## **15. Release Readiness**

The MVP is ready for internal use when all acceptance criteria pass in a test organization, direct GitHub write paths for regular developers have been removed and tested, failure recovery is demonstrated, audit records are available, and the organization-owner limitation is documented. Before onboarding production repositories, the team must also define temporary object retention, backup/recovery behavior for in-flight pushes, operational alerts, and incident response for a compromised gateway or GitHub App credential.

Sordian • Version 0.1 • 7
