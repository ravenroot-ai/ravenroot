# Ravenroot GitHub automation extension

`ai.ravenroot.extensions.github` is an optional Node SDK `/2` bundle. It contributes exactly five
behaviors: `github-events-source`, `project-transition`, `github-app-review`,
`github-workflow-watch`, and `release-prepare`. The bundle is not part of the default distribution;
an operator builds, installs, enables, profiles, and grants it explicitly.

## Authority and authentication

The bundle receives no GitHub token, webhook secret, repository URL, Project field identifier, or
release authority from GraphML or an input payload. A graph carries only an opaque `githubProfile`.
Profiles are qualified by the trusted Ravenroot tenant. The
runtime injects GitHub App installation credentials through a destination-bound managed HTTP
credential binding. Only webhook verification uses raw credential resolution, and its short-lived
lease is erased immediately after HMAC computation.

The configured installation ID is verified against signed webhook payloads. For outbound calls, the
bundle trusts the operator and Ravenroot managed credential service to bind the opaque credential
reference to the declared installation/App identity. A repository-identity GET verifies that the
review credential resolves the configured numeric repository and owner/name, but the GitHub
responses used here do not independently attest the installation ID. This is an explicit managed
credential-service trust boundary.

The managed HTTP grant should admit only `https://api.github.com`, the methods each installed graph
actually needs, the request headers `accept`, `content-type`, `user-agent`, and
`x-github-api-version`, and the response headers `retry-after`, `x-ratelimit-remaining`, and
`x-ratelimit-reset`. GitHub App permissions can be narrowed by installation:

| Operation | Repository or organization permission |
|---|---|
| read a pull request and its reviews | Pull requests: read |
| submit a formal review | Pull requests: write |
| read workflow runs | Actions: read |
| read repository contents and commits | Contents: read; Metadata: read |
| update a Project v2 item | Organization Projects: write |

Use separate profiles/installations when read-only release preparation or workflow observation must
not inherit review or Project mutation permission.

## Authenticated webhook relay

Ravenroot managed ingress authenticates and authorizes a Ravenroot principal before an extension
handler runs. GitHub webhooks do not carry that credential. Therefore GitHub must not call the
managed route directly: an operator-managed relay authenticates to the Ravenroot listener as the
target tenant and forwards the original, unchanged body plus exactly `X-Hub-Signature-256`,
`X-GitHub-Delivery`, and `X-GitHub-Event`. The relay must not parse, reserialize, decompress, or
otherwise transform the body because the bundle verifies the GitHub HMAC over those exact bytes.

The bundle accepts only `sha256=<64 lowercase hexadecimal characters>`, compares the HMAC in
constant time, and performs no JSON parse or durable offer before verification. It then checks the
signed repository and installation IDs and the configured event/action allowlist. Before offering
custody, it durably binds the tenant/profile/delivery ID to the signed body digest plus event,
action, repository ID, and installation ID. Exact replay is accepted; reuse of a delivery ID for
different signed content fails closed with `409`. `DurablyCommitted` and `Duplicate` return `202`; invalid
signatures return `401`, disallowed authority returns `403`, full admission returns `429`, and
volatile or ambiguous custody returns `503`. This remains the platform's documented single-replica
durability boundary.

Recommended subscriptions are only the events used by installed graphs, such as `pull_request`,
`pull_request_review`, `workflow_run`, or `projects_v2_item`. Configure explicit allowed actions for
each event; an empty action set means an event without an `action` member is allowed.

## Operations and outcomes

### `project-transition`

Input `github.project-transition.v1` supplies an item ID, expected status, target status, expected
generation, expected Attempts value, and correlation ID. The profile fixes the Project, field IDs,
status-option IDs, allowed transitions, and the one claim transition that increments Attempts.
Claims write `expectedAttempts + 1`; all other transitions preserve the exact expected value.
Writes are absolute, recorded as durable intent, and followed by a complete remote reread. Replay of
the exact desired status/Attempts/generation is `already-applied`; a lost generation or mixed remote
state is `conflict`/`CAS_LOST` and is never overwritten.

The local fence is tenant/profile plus numeric repository and Project item, independent of target
status and correlation ID, and stores the complete request digest. The item must be a repository
Issue or Pull Request from that exact numeric repository; draft and repository-less items are
rejected. The configured fields must all occur in the first 100 values and `hasNextPage` must be
false. Any partial or otherwise non-definitive dispatched mutation response is followed by a full
snapshot and succeeds only if status, absolute Attempts, and generation all match.

GitHub Project v2 does not expose an atomic conditional field update. This is optimistic CAS safety,
not a claim of provider-side linearizability. Another writer can race between read and mutation.
Deploy one writer for a profile and treat every conflicting or partial state as operator-visible
reconciliation work.

### `github-app-review`

Input `github.app-review.v1` supplies a PR number, exact 40-character commit SHA, verdict, bounded
body, and correlation ID. The bundle discovers an existing content-bound review before mutation,
checks the PR head, creates a pending review for the supplied commit, checks the head again, and only
then submits. It verifies the configured App login, review ID, commit, and final PR head afterward.
`stale` is terminal and is never counted or resent; an unknown submit result is `ambiguous` and is
reconciled by review discovery before any later attempt. Discovery is capped at 1,000 reviews and
fails closed rather than creating a possible duplicate beyond that bound.

GitHub has no atomic “submit only if still head” precondition. A push can occur after the final head
read and before submission. The post-submit reread reports that review as `stale`; branch protection
should dismiss stale approvals where an approval is authoritative. The bundle never claims this
unavoidable API race is absent.

### `github-workflow-watch`

Input `github.workflow-watch.v1` fixes a commit SHA, absolute deadline, and correlation ID. The
profile fixes the complete required workflow-ID set. The bundle persists the commit, deadline,
poll count, run identities, attempts, and state before provider backoff. Recovery reacquires an
expired writer lease and re-queries the same SHA; it does not infer success from a branch name.
Each poll is separately scheduled, so no worker thread or profile concurrency permit is held during
the wait. Persisted per-workflow observations are retained when an eventually consistent response
temporarily omits them and are replaced only by an authoritatively newer observation. Distinct runs
are ordered by `run_number`, then `created_at` and run ID; `run_attempt` orders only attempts of the
same run. The exact-SHA query is bounded to 100 results and fails closed if GitHub reports more.
Outcomes are `continue` only when every required run
completed with `success`, `failed` for a terminal non-success conclusion, and `timeout` for deadline
or poll exhaustion. Missing runs remain pending. `Retry-After` and rate-limit reset are honored
within the absolute deadline, and cancellation aborts the active managed HTTP call and further polls.

### `release-prepare`

Input `github.release-prepare.v1` supplies an exact commit, an allowed release kind, and correlation
ID. The profile fixes the repository, release branch, version file, change-fragment directory, and
bounds. The node verifies the exact commit is still the configured release-branch head, reads only
that commit, and returns an advisory next version, tag name, source
digests, ordered fragments, and bounded note text.

The Maven version is parsed structurally from the direct `/project/version` element, never from
`modelVersion` or a parent coordinate, and the release-branch head is read again after all metadata.
All file counts and decoded aggregate output are bounded by the profile limits.

This node is structurally read-only: its implementation exposes only GET operations. It cannot
create or update a ref, branch, commit, pull request, tag, release, asset, deployment, workflow
dispatch, merge, or publication. It does not invoke or duplicate Ravenroot's protected release
authorization and publication workflows.

## Durable bundle state and audit evidence

`store.path` names an operator-owned SQLite file. Schema migration is forward-only. Every operation
is partitioned by tenant/profile/kind/key, content-bound by SHA-256, and protected by an expiring
single-writer lease renewed before and after managed I/O and by a bounded-operation heartbeat.
Terminal success, failure, cancellation, stale, conflict, and timeout outputs are ownerless and
replay deterministically for the same request digest. A subsequent Project request may replace a
non-ambiguous terminal row only when its expected generation equals the ledger's stored observed
generation; it still performs a fresh remote CAS snapshot. Mutating `ambiguous` rows alone may be
reacquired for the same digest, and only for strict remote reconciliation before another mutation.
`maxOperations` applies independently to operation,
delivery-deduplication, and audit retention per tenant/profile. Expired stale running/waiting rows
and old terminal evidence are reclaimed after `retentionHours`, preventing permanent quota
exhaustion. Use one shared store for every process that serves the same profile.

The bundle audit table contains only tenant/profile structural keys, operation kind/key, timestamp,
disposition, stable reason, and SHA-256 evidence digest. It never stores credentials, webhook
signatures, raw HTTP errors, response bodies, review bodies, GitHub tokens, or endpoint-derived
diagnostics. This is bounded package operation evidence, not a second Ravenroot platform audit
authority.

All GitHub calls recognize `429`, `403` with `Retry-After`, and `403` with
`X-RateLimit-Remaining: 0`. Provider reset times are clamped to a short positive minimum and a
five-minute maximum; operation deadlines, poll counts, response bounds, cancellation, and profile
concurrency remain authoritative.

## Configuration and limits

Set `RAVENROOT_GITHUB_CONFIG` to canonical Base64 of strict JSON. Unknown fields, non-canonical
Base64, unsafe identifiers, plaintext API origins, overlapping authority, excessive limits, and
incomplete profiles fail package activation. The top-level shape is:

```json
{
  "authority": {
    "listenerId": "main",
    "pathPrefix": "/managed/github",
    "requiredScopes": ["github:webhook"],
    "maxRoutes": 8,
    "maxConcurrentRequests": 32,
    "maxRequestBytes": 1048576,
    "maxResponseBytes": 4096,
    "requestTimeoutMs": 5000
  },
  "projection": {
    "maxRelativePathBytes": 256,
    "maxQueryParameters": 1,
    "maxQueryBytes": 256,
    "maxHeaderCount": 3,
    "maxHeaderBytes": 1024,
    "maxHeaderValueBytes": 512
  },
  "store": {
    "path": "/var/lib/ravenroot/github-operations.db",
    "maxOperations": 100000,
    "retentionHours": 720,
    "leaseMs": 30000
  },
  "profiles": {
    "automation": {
      "tenantId": "tenant-a",
      "apiOrigin": "https://api.github.com",
      "owner": "example",
      "repository": "service",
      "repositoryId": 1234,
      "installationId": 5678,
      "reviewerLogin": "example-reviewer[bot]",
      "credentialBindingId": "github-installation",
      "credentialReference": "github-installation-token",
      "webhookSecretReference": "github-webhook-secret",
      "route": "/automation",
      "events": {"pull_request": ["opened", "synchronize"], "workflow_run": ["completed"]},
      "project": {
        "projectId": "PVT_example",
        "statusFieldId": "PVTSSF_status",
        "attemptsFieldId": "PVTF_attempts",
        "generationFieldId": "PVTF_generation",
        "statusOptions": {"Todo": "todo-id", "InProgress": "progress-id", "Done": "done-id"},
        "allowedTransitions": ["Todo->InProgress", "InProgress->Done", "InProgress->Todo"],
        "claimTransition": "Todo->InProgress"
      },
      "workflowIds": [1001, 1002],
      "release": {
        "branch": "main",
        "versionPath": "ravenroot/pom.xml",
        "fragmentsPath": ".changes",
        "allowedKinds": ["none", "patch", "minor", "major"],
        "maxFiles": 256
      },
      "limits": {
        "timeoutMs": 10000,
        "maxRequestBytes": 1048576,
        "maxResponseBytes": 1048576,
        "maxConcurrency": 8,
        "maxPolls": 120,
        "pollIntervalMs": 5000
      }
    }
  }
}
```

The package-level hard maxima are 16 MiB managed ingress, 2 MiB GitHub request/response, 128
concurrent operations per profile, 1,000 workflow polls, 60 seconds between polls, one million
retained operations, and one year of retained package evidence. Profiles may only lower those
bounds; graphs cannot change them.

The current Node SDK `NodeTypeDescriptor` exposes behavior identity, properties, capabilities, and
execution flags, but has no machine-readable input/output payload-schema fields. This bundle
therefore enforces strict versioned payload shapes at runtime and documents them above; it does not
claim descriptor-published schemas that the contract cannot represent.

## Build and install

```sh
mvn -f ravenroot/pom.xml -pl ravenroot-extensions/ravenroot-github -am verify
./plugin.sh build github
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-github/target/plugin-bundle
```

Enable the validated bundle through the ordinary plugin allowlist. `plugin.sh` discovers the module
from its single `GithubNodePackage`; no runtime classpath scanning or `ServiceLoader` trust expansion
is used.
