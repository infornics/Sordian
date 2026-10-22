# 06: Testing & Verification Playbook

This document provides a step-by-step verification guide for Module 1, covering automated testing, terminal Git CLI testing against a real repository, and manual API testing via Postman.

---

## 1. Automated Test Suite (Gradle)

Module 1 includes automated unit and MockMvc integration tests verifying wire parsing, HTTP headers, invariant inspection, and upstream forwarding.

### 1.1 Running the Tests
Execute the Gradle test runner from the `backend/` directory:
```bash
cd backend
./gradlew test
```

### 1.2 Test Inventory

| Test Class | Test Method | Verification Target |
| :--- | :--- | :--- |
| `GitPktLineParserTest` | `shouldParseStandardCommandAndFlush` | Validates extraction of `<old-oid>`, `<new-oid>`, and `<ref-name>` up to `0000`. |
| `GitPktLineParserTest` | `shouldExtractCapabilitiesFromFirstLine` | Validates extraction of client capabilities (`report-status`, `side-band-64k`) separated by `\0`. |
| `GitSmartHttpControllerTest` | `shouldRejectNonReceivePackServices` | Asserts that `GET /info/refs?service=git-upload-pack` returns HTTP 403 Forbidden. |
| `GitSmartHttpControllerTest` | `shouldAdvertiseRefsForReceivePack` | Asserts that `GET /info/refs?service=git-receive-pack` returns HTTP 200 with advertisement headers. |
| `GitSmartHttpControllerTest` | `shouldReturnGitRejectionWhenInspectionFails` | Asserts that branch deletion attempts return Git wire `ng` status without unpack. |
| `GitSmartHttpControllerTest` | `shouldReturnOkWhenPushSuccessfullyForwardsUpstream` | Asserts end-to-end receipt of `unpack ok` and `ok refs/heads/<branch>` upon successful upstream forward. |

---

## 2. Live Terminal Verification (Git CLI)

To test Sordian against a real GitHub repository (e.g. `https://github.com/rachit-bharadwaj/delete-later`):

### Step 1: Start the Sordian Gateway
In your backend terminal:
```bash
cd backend
./gradlew bootRun
```
Confirm the application has started on port 8080:
```text
INFO ... Started SordianApplication in 1.25 seconds
```

### Step 2: Prepare a Test Repository
In a new terminal window:
```bash
# Clone the repository from GitHub
cd /tmp
git clone https://github.com/rachit-bharadwaj/delete-later.git test-repo
cd test-repo

# Configure Sordian as a Git remote
git remote add sordian http://localhost:8080/rachit-bharadwaj/delete-later.git
```

### Step 3: Test a Permitted Feature Push
Create a new feature branch, add a commit, and push through Sordian:
```bash
git checkout -b test-feature
echo "Verified via Sordian" > test.txt
git add test.txt
git commit -m "feat: test verified push via Sordian gateway"

# Push through Sordian gateway
git push sordian test-feature
```

#### Expected Terminal Output:
```text
Enumerating objects: 4, done.
Counting objects: 100% (4/4), done.
Writing objects: 100% (4/4), 380 bytes | 380.00 KiB/s, done.
Total 4 (delta 0), reused 0 (delta 0), pack-reused 0
To http://localhost:8080/rachit-bharadwaj/delete-later.git
 * [new branch]      test-feature -> test-feature
```

---

## 3. Postman API Test Suite

The Postman collection is located at [`docs/Sordian_Postman_Collection.json`](file:///home/rachit/Documents/Code/Infornics/Marketplace/Sordian/docs/Sordian_Postman_Collection.json).

### Collection Variables
- `{{localBaseUrl}}`: `http://localhost:8080`
- `{{org}}`: `rachit-bharadwaj`
- `{{repo}}`: `delete-later`

---

### Request 1: Service Discovery & Ref Advertisement
- **Path:** `01. Git Smart HTTP Transport > 01 - Advertise Refs`
- **Method:** `GET`
- **URL:** `{{localBaseUrl}}/{{org}}/{{repo}}.git/info/refs?service=git-receive-pack`
- **Expected Response:** `200 OK`
- **Response Headers:** `Content-Type: application/x-git-receive-pack-advertisement`

---

### Request 2: Reject Branch Deletion Attempt
- **Path:** `02. Git Ref Inspection & Enforcement > 01 - Reject Branch Deletion Attempt`
- **Method:** `POST`
- **URL:** `{{localBaseUrl}}/{{org}}/{{repo}}.git/git-receive-pack`
- **Headers:**
  - `Content-Type`: `application/x-git-receive-pack-request`
  - `Accept`: `application/x-git-receive-pack-result`
- **Body (raw):**
  ```text
  005e7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b 0000000000000000000000000000000000000000 refs/heads/delete-this
  0000
  ```
- **Expected Wire Response:**
  ```text
  000eunpack ok
  0069ng refs/heads/delete-this [SORDIAN REJECTED] Branch deletion is denied by default to prevent history reset.
  0000
  ```

---

### Request 3: Reject Non-Fast-Forward / Force-Push Attempt
- **Path:** `02. Git Ref Inspection & Enforcement > 02 - Reject Non-Fast-Forward / Force Push Attempt`
- **Method:** `POST`
- **URL:** `{{localBaseUrl}}/{{org}}/{{repo}}.git/git-receive-pack`
- **Headers:**
  - `Content-Type`: `application/x-git-receive-pack-request`
  - `Accept`: `application/x-git-receive-pack-result`
- **Body (raw):**
  ```text
  005e1111111111111111111111111111111111111111 2222222222222222222222222222222222222222 refs/heads/delete-this
  0000
  ```
- **Expected Wire Response:**
  ```text
  000eunpack ok
  0083ng refs/heads/delete-this [SORDIAN REJECTED] Non-fast-forward updates are strictly prohibited. Rewriting history is forbidden.
  0000
  ```

---

## 4. Troubleshooting Common Issues

### Issue 1: `Connection refused on port 8080`
- **Cause:** The Spring Boot backend process is stopped or still starting.
- **Remedy:** Wait for the terminal log: `Started SordianApplication in ... seconds` before issuing client requests.

### Issue 2: `unpack error unpack-failed` in Postman
- **Cause:** When testing `03 - Forward Approved Fast-Forward Push to Upstream` via Postman with raw text, only the preamble is sent without the accompanying binary `.pack` file. Git's internal unpacker cannot find commit objects.
- **Remedy:** This is expected when simulating pushes without packfile payloads. For full end-to-end commit validation, use the Git CLI (`git push sordian <branch>`) which automatically attaches the packfile.

### Issue 3: `send-pack: protocol error: bad band #117`
- **Cause:** Sideband channel byte `0x01` was omitted when client requested `side-band-64k`.
- **Remedy:** Verify that `writeStatusLine` in `GitSmartHttpController` is used to encapsulate status responses in Band #1.
