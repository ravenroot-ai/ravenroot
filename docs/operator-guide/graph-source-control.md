# Graph source control and published deployments

Ravenroot keeps local-file authoring as its default. Set `RAVENROOT_GRAPH_AUTHORING_MODE=GIT` to let the workspace use a server-configured Git repository. The browser never chooses a repository, path, API endpoint, branch, credential, or tenant namespace. It receives only the operations and display metadata that the server enables.

## Configure the source archive

Git mode supports GitHub, GitHub Enterprise, GitLab.com and GitLab Self-Managed through a provider-neutral application port. Configure these values on the server:

| Setting | Purpose |
|---|---|
| `RAVENROOT_GRAPH_AUTHORING_MODE` | Startup enum `LOCAL` or `GIT`. Unset defaults to `LOCAL`, which ignores the remaining Git authoring and artifact bindings. |
| `RAVENROOT_GRAPH_AUTHORING_PROVIDER` | Git-mode provider enum. Unset defaults to `GITHUB`; select `GITLAB` for GitLab.com or Self-Managed. |
| `RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER` | Required GitHub account or organization. It accepts 1–100 ASCII letters, digits, `_`, `.`, or `-`. |
| `RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME` | Required operator-owned repository name with the same 1–100 character boundary. |
| `RAVENROOT_GRAPH_AUTHORING_GRAPH_DIRECTORY` | Confined repository-relative graph root, default `graphs`, with 1–512 allowed path characters and no absolute, trailing, doubled, or `..` segment. |
| `RAVENROOT_GRAPH_AUTHORING_DRAFT_BRANCH` | Tenant-draft branch prefix, default `draft`, with 1–200 allowed branch characters. It must differ from the release branch. |
| `RAVENROOT_GRAPH_AUTHORING_RELEASE_BRANCH` | Reviewed graph release branch, default `main`, with 1–200 allowed branch characters. It must differ from the draft prefix. |
| `RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES` | Required comma-separated grants such as `tenant-a=customers/a,tenant-b=customers/b`. Namespaces are confined; duplicate tenants and colliding derived draft branches are refused, and an absent tenant has no repository access. |
| `RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE` | Absolute HTTPS API origin/path, default `https://api.github.com`. An Enterprise prefix such as `/api/v3` is retained; credentials, query, and fragment are refused. |
| `RAVENROOT_GRAPH_AUTHORING_GITLAB_API_BASE` | GitLab HTTPS API base, default `https://gitlab.com/api/v4`; Self-Managed instance prefixes are preserved and the path must end in `/api/v4`. No credentials, query or fragment. |
| `RAVENROOT_GRAPH_AUTHORING_GITLAB_PROJECT` | Required for GitLab: positive project ID or complete path such as `group/subgroup/graphs`. The entire path is encoded as one API parameter; GitHub owner/name settings are ignored. |
| `RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE` | Required `APP` or `PAT` for GitHub; GitLab accepts `PAT` only. `PAT` resolves a server-side token; `APP` mints a repository-scoped installation token. |
| `RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE` | Required opaque server-side credential name resolving the PAT or App private key. It is never caller-selected or returned to the browser or logs. |
| `RAVENROOT_GRAPH_AUTHORING_GITHUB_APP_ID` | Required only for `APP`; positive decimal App identifier of 1–20 digits. It is an identifier, not a credential. |
| `RAVENROOT_GRAPH_AUTHORING_GITHUB_INSTALLATION_ID` | Required only for `APP`; positive decimal installation identifier of 1–20 digits. The installation token is restricted to the configured repository. |
| `RAVENROOT_GRAPH_ARTIFACT_BASE_URL` | Required absolute HTTPS immutable-artifact base ending in `/`, without credentials, query, or fragment. Outbound access is confined to this origin. |
| `RAVENROOT_GRAPH_ARTIFACT_CATALOG_PATH` | Confined relative immutable catalog snapshot path, default `catalog.json`; absolute, trailing, doubled, or `..` paths are refused. |
| `RAVENROOT_GRAPH_AUTHORING_MAX_DOCUMENT_BYTES` | GraphML request, Git blob, and downloaded artifact ceiling. Default `10485760`; accepted Git-mode range 1–`104857600` bytes. |
| `RAVENROOT_GRAPH_AUTHORING_PAGE_SIZE` | Document and history page size. Default `50`; accepted range 1–200 for GitHub, 1–100 for GitLab. |
| `RAVENROOT_GRAPH_AUTHORING_REQUEST_TIMEOUT_MILLIS` | Positive connect, request, and complete response-body deadline. Default `15000`; maximum `120000` milliseconds. |
| `RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT` | Additional retries for eligible provider `GET` failures. Default `1`; accepted range 0–3. Mutations are never retried blindly. |

For a GitHub App, also set the App and installation IDs. Store the PKCS#8/PKCS#1 private key at the credential reference. The adapter mints a short-lived installation token scoped to the configured repository with contents, pull-request, and metadata permissions. A PAT is resolved through the same server-only credential chain. The Helm chart uses the fixed reference `git-authoring` and reads its encoded environment binding from a Kubernetes Secret.

### Embedded server integration

An embedded host can opt into the same Git repository, published artifact catalog, admission checks,
and provider adapters as the packaged server. Build the ordinary application and server, then apply
one public configuration operation before starting the listener:

```java
var authoring = EmbeddedGraphAuthoringConfiguration.fromEnvironment(
        environment, credentialResolver);
try (var server = new RavenrootServer(application, address, uiDirectory, authenticator)
        .configureGraphAuthoring(authoring)) {
    server.start();
    // Serve until the host's normal shutdown signal.
}
```

`environment` uses the settings in the table above. `credentialResolver` receives only the opaque
`RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE`; it must return the referenced PAT or GitHub App
private key from the host's trusted secret boundary. Git mode validates that the reference resolves
to nonblank credential material during configuration and reports only
`Git graph authoring credential is unavailable` when it does not. The diagnostic never contains the
reference, credential, or resolver failure. GitHub PAT, GitHub App, Enterprise API base, tenant
namespace, and artifact settings are identical to the packaged server settings.

The application must expose an immutable graph definition store. `DefaultRavenrootApplication`
reports this capability when it is constructed with a `GraphDefinitionStore`; a custom
`RavenrootApplication` must implement `immutableGraphDefinitionsAvailable()` and the existing
published-definition and pinned-deployment operations against its immutable store. Git mode fails
during configuration when this capability is absent. Leaving the mode unset selects `LOCAL`, does
not resolve a credential, and preserves the existing disabled embedded authoring routes.

The configured server retains its existing authenticator, authorization service, and tenant
isolation. It owns the application and any authoring repository after construction. `close()` releases
those components; a configuration or `start()` failure also closes the listener, executor,
repository, and application before the failure is returned.

For an embedded host that previously used package access, reflection, or a bridge to call
`installGraphAuthoring` and `installPublishedGraphArtifacts`, remove that composition and construct
`EmbeddedGraphAuthoringConfiguration` instead. Do not create a second artifact catalog or repository:
`configureGraphAuthoring` creates and binds both routes as one operation, using the same composition
as `RavenrootServerMain`.

`ravenroot.graph.inspect` reads source. `ravenroot.graph.write` saves, restores, discards, or deletes drafts. `ravenroot.graph.release` proposes review in the graph repository. `ravenroot.graph.deploy.published` registers a verified publication. Tenant ownership checks still apply after scope and role checks; graph scopes alone never grant another tenant's configured namespace.

## Version and release behavior

Every saved document carries graph-scoped `ravenroot.authoring.graphId` and `ravenroot.authoring.releaseVersion` metadata. The stable graph ID is separate from a runtime content digest. The positive authored release version is separate from GraphML schema versions and runtime aggregate versions. It is a canonical decimal integer from 1 through `9007199254740991`; leading zeroes, signs, fractions, exponents, and larger values are refused consistently by the browser, server, and publisher.

Opening or saving unchanged content does not increment the authored version. The first changed save after the release branch contains the previous draft increments it once; later saves on that draft keep the incremented value. Every mutation checks the observed draft ref, release ref, and publication token, then uses the provider concurrency mechanism described below (GitHub uses a non-force expected-parent update). A stale browser gets `409` and must reopen before retrying.

A release action creates or reuses a pull request on GitHub or a merge request on GitLab from the tenant draft branch to the configured graph release branch. It grants no authority over Ravenroot's product repository or `main` branch. Deleting a draft removes the branch file while Git history remains the archive.

## Publish and deploy

Release CI must validate the exact release-branch GraphML through `/v1/graphs/inspect?purpose=LOCAL_DEPLOYMENT`, produce an external digest manifest, and upload GraphML, manifest, and catalog snapshot with conditional create semantics. The example at `docs/examples/graph-authoring` supports immutable HTTPS `PUT` and S3-compatible object storage. Each snapshot retains prior releases, binds releases to source commits, rejects reused or decreasing versions, verifies bytes on an idempotent retry, and records the node-package identities and active tenant program artifacts that the runtime actually resolved during admission.

The server downloads the configured catalog, manifest, and GraphML through its bounded outbound policy. It verifies source-commit binding, both SHA-256 digests, the manifest contract, tenant, authored identity, compatibility admission, and GraphML metadata. Before publication and again before import or deployment, every referenced node package must be installed with the same identity. Every `program` node's exact source must already have an active tenant-scoped artifact compatible with the current program runtime. Operators provision those program artifacts through the existing governed program-artifact lifecycle; publication does not compile, activate, or execute code. Resolution uses GraphML `attr.name` metadata, so arbitrary GraphML key IDs do not weaken the binding.

Current runtime admission runs again when `POST /v1/graph-artifacts/{graphId}/{version}/import` pins the bytes in the tenant-scoped immutable `GraphDefinitionStore`. Git mode refuses to start without that store. The request binds the catalog selection by both the GraphML digest and immutable manifest-and-graph artifact reference, so a changed catalog cannot substitute another release after selection. A separate `.../deploy` request resolves and verifies that exact pinned digest before registering a stopped deployment, and returns the digest of the bytes that were registered. Running it remains a separate lifecycle command, and Git branches are never executable. Selecting an older published version through **File → Published versions** imports and registers those retained immutable bytes for rollback without stopping an existing deployment.

Audit events record request ID, hashed tenant and subject, operation, resource, and outcome. They exclude GraphML, tokens, repository credentials, and provider response bodies.

## Recovery

For an uncertain save or release response, retry with the same idempotency key. Keys are bounded to tenant, subject, operation, and request fingerprint. The server reconciles Git commits by its fingerprint marker and pull requests by exact head and base SHAs. A different request using the same key is refused.

Back up the graph repository and immutable artifact store through their normal provider controls. Ravenroot's runtime database does not become a second draft archive. After restoring Ravenroot, configure the same repository, tenant mappings, and immutable catalog snapshot; the UI derives draft, release, and publication state from those authorities again.

## GitLab baseline, concurrency and operation

The supported minimum is **GitLab 17.7 with REST API v4**, for GitLab.com and Self-Managed.
The implementation uses the documented non-recursive tree, blob, file-metadata, branch,
multi-action commit and merge-request endpoints, without newer `with_last_commit` tree fields.
Verification consists of executable HTTP transport fixtures and the primary
[commits](https://docs.gitlab.com/api/commits/),
[repositories](https://docs.gitlab.com/api/repositories/) and
[merge requests](https://docs.gitlab.com/api/merge_requests/) API contracts. Live hosted GitLab
and Self-Managed deployment tests have not been performed. Configure a trusted TLS certificate
chain and preserve encoded slashes through a Self-Managed reverse proxy. Redirects are refused;
credentials are sent only to the configured HTTPS host and port.

Use a project access token or personal access token with `api` scope and the least project role
that can create tenant draft branches, commit files and propose merge requests. The token must
not permit unreviewed writes to the release branch. GitHub App installation authentication is
not a GitLab credential mode. Configure `graphAuthoring.provider: gitlab`, `gitlabProject`,
`gitlabApiBase`, `credentialMode: PAT`, tenant mappings and the credential Secret in Helm.
The same repository operations and artifact import/deploy actions remain available.

GitLab `last_commit_id` protects files, not a globally selected branch parent. Each authoring
commit therefore atomically updates the tenant's `.ravenroot-authoring.json` coordination file,
creates a hashed per-request record in `.ravenroot-authoring/requests/`, and changes the GraphML.
First coordination creation and later conditional updates serialize adapter instances across
documents. Request records are directly addressed, retained without a lifetime operation ceiling,
and bind tenant, subject, operation, key and complete request fingerprint. Do not edit or delete
these internal files. They contain hashed attribution, not tokens or GraphML. Reusing a key with
another fingerprint is refused; a replay superseded by later source/publication state may return
a conflict and never creates another write. Missing draft branches are provisioned only on writes.

The adapter checks the resulting commit's parent and reobserves release/publication after writing.
A detected stale mutation is cancelled with a new conditional commit restoring only that operation's
GraphML and recording cancellation. Subsequent changes to the affected file or coordination state
prevent compensation; an ambiguous result fails safely. There is no transaction spanning external
Git writers, a release merge and object storage: operators must protect the release branch and
publish immutable reviewed commits. External changes may race or briefly expose an attempted draft
in Git history; they never authorize execution. Neither refs nor third-party work are force-reset.
A rejected request can therefore leave an auditable attempted/cancellation commit pair. Reopen and
reconcile before a new edit. GitLab's uniqueness of an open source/target merge request prevents
concurrent duplicate proposals; unresolved diff computation returns an error and a later retry
reuses the existing proposal. Proposals bind the exact source and target heads.

Each tree or merge-request search is limited to 20 pages of 100 entries, history to 20 pages of the
configured size, each operation to 4096 provider requests, and request records to 4096 decoded bytes.
An over-limit request fails explicitly rather than returning an incomplete archive. HTTP response
bytes, outbound request bytes, document size and request deadlines are bounded. GitLab mutation
responses are never blindly retried. Provider failures exclude credential material and response bodies.

Use [the GitLab CI example](../examples/graph-authoring/gitlab-ci.yml) on a protected release
branch with required merge approvals. Configure protected/masked admission and storage credentials,
a reviewed full tooling commit, and the immutable catalog predecessor. MR pipelines never receive
publication credentials. The publisher validates admission, conditionally creates immutable bytes,
manifests and catalog snapshots, and verifies existing bytes on rerun. Import and deployment verify
the exact immutable selection through the existing catalog boundary; registration remains stopped.

## Persistent authoring read index

The shipped Git composition keeps a disposable authoring projection separate from executable graph
admission. `RAVENROOT_EXECUTION_STORE=sqlite` selects a separate SQLite/WAL file,
`ravenroot-authoring-read-index.db`, under `RAVENROOT_EXECUTION_STORE_DIR`, or
`./data/execution-store` when unset. PostgreSQL deployments use an owned pool with the existing
shared-database connection settings and bounds, with dedicated `authoring_read_*` tables. Account for
this pool separately from execution persistence when budgeting database connections. The existing
execution persistence selector and system-property precedence are reused. Embedded environment
composition snapshots only the resolved persistence settings when configured; it does not retain
unrelated environment values. Both providers work with either
backend; GitHub PAT and App credentials retain their existing configuration.

The schema is additive and versioned independently of execution persistence. A future index schema
is refused. To rebuild, stop all servers sharing the projection, preserve any evidence needed for
investigation, and remove only the SQLite authoring-index file and its WAL/SHM sidecars, or the
PostgreSQL `authoring_read_*` tables. Never remove execution-definition, manifest or execution-state
tables. Restart rebuilds authoring summaries from remote source and verified publication evidence.
An interrupted synchronization leaves the preceding complete generation visible; it never publishes
half an inventory. A failed rebuild fails the read and can be retried after the cause is repaired.

Every operation checks current repository access, including cache hits and continuation pages.
First-page reads freshly validate draft/release refs and the independent artifact catalog. An
unchanged indexed generation needs no remote GraphML bodies. A changed document reuses unchanged
blob metadata and downloads only changed content. Publication-only changes reconcile independently.
Provider and document response limits remain in force: GitHub truncated trees are refused, and
GitLab enumerates all bounded non-recursive tree pages or fails rather than returning a truncated
inventory. The publication catalog still admits at most 200 entries. No implicit offline or stale
fallback is provided.

New list continuation cursors are opaque, generation-bound strings. Pass `nextCursor` unchanged;
the response shape and page size are unchanged. Existing nonnegative numeric cursors through 100000
remain accepted with their previous meaning (page number against a fresh observation). Returned
opaque cursors retain deterministic document-ID ordering across insertions, deletions and branch
rewrites. A tenant retains up to 16 generations; publication prunes superseded generations older than
15 minutes. A pruned or foreign cursor is invalid: restart at the first page. History remains a
separate, on-demand paginated operation; immutable history pages and explicit-commit differences
have independent bounded caches.

The composition reconciles each configured tenant every 60 seconds as recovery for external edits
and missed notifications. Reads keep their fresh-validation behavior between scheduled runs. There
is no automatically registered provider webhook endpoint. An embedder with an authenticated webhook
receiver may call `GraphAuthoringRepositories.signal(repository, tenantId)` on a standalone or decorated
managed repository;
verify signature, provider project and tenant mapping before doing so. Signals carry no authoritative
revision and coalesce; a signal arriving during reconciliation schedules another fresh observation.
Duplicate or lost notifications cannot replace remote validation. Shutdown stops scheduled work and
closes the owned archive/index/pool.

The archive exposes coarse `readStatistics()` counters for hit, miss, eviction, bypass, retained
in-memory blob bytes, parsed tree/commit/history/diff retained bytes, in-flight loads, provider calls, total and per-operation nanoseconds,
synchronization duration and validation lag. The managed reconciler exposes completion, failure and
lag counters, and the publication catalog exposes call count and retained artifact bytes. Export
these through the embedding application's metrics system using fixed operation labels; never label
metrics with tenant IDs, document paths, subjects or credential values. SQL retained-content bytes
are available through `GraphAuthoringReadIndex.retainedBytes()`.

Each adapter admits at most 128 operations with at most 64 executing concurrently. Cancellation
retains admission until underlying work terminates; cancellation before start and submission rejection
release the slot exactly once. Immutable blob
loads admit at most 64 distinct identities and execute at most 16 loaders. Blob caches retain at
most 2048 entries/64 MiB in memory and separately 2048 entries/64 MiB in SQL. Parsed immutable
trees retain at most 2048 entries/64 MiB per adapter; GitHub commit-to-tree mappings retain at most
2048 entries/1 MiB. These caches are separate from optional HTTP validators and are used only by
reads; mutation tree and compare-and-swap checks remain remote. Each synchronization retains at
most 16 metadata futures at a time, with at most 16 running and 64 queued loads across the adapter,
and cancels unfinished work on failure or deadline. Conditional response
representations retain at most 1024 entries/16 MiB; parsed list snapshot objects are scoped to one
operation, with response bytes capped at 64 MiB. History and diff caches each retain at most 512
entries/8 MiB; verified publication artifacts retain at most 200 entries/64 MiB. Oversized optional
cache entries bypass retention. Failed loads are never cached. Content admission verifies the Git
blob identity and stored SHA-256; authorization and mutation revision checks remain remote.
Eligible GET retries honor `Retry-After` and rate-limit headers. A rate-limit 403 is unavailable,
not an authorization failure. Delays beyond the request timeout or 30 seconds fail promptly rather
than retrying before the provider permits. Ordinary authorization failures are not retried as rate
limits. Conditional responses are accepted only with the exact previously validated representation.

Remote writes still use the provider's original expected-revision and validation checks. The index
is never write authority. Optional content-retention failures increment bypass counters and do not
turn a successful remote mutation into a reported write failure. The next fresh read reconciles a
remote success, an ambiguous write, or an external branch change before using its projection.

Embedded hosts may use `EmbeddedGraphAuthoringConfiguration.decorateRepository(...)` with
`ForwardingGraphAuthoringRepository` to add application behavior without copying server bootstrap.
The supplied delegate is a non-owning view of the admitted archive. The composition invokes the
decorator's close hook and the owned archive close exactly once, including construction failures;
normal writes still pass through admission and publication checks. Directly constructed provider
adapters use a bounded volatile projection; use the shipped composition or supply a persistent
index through the supported composition factory for restart reuse.


## Reproducing the local fixture measurements

The [measurement artifact](../benchmarks/authoring-read-index.json) records deterministic fixture
results on an Apple M1 Max, 32 GiB RAM, Java 21.0.11, SQLite/WAL and local Docker PostgreSQL 17.
The provider fixtures add no artificial latency and make no live GitHub/GitLab requests. These
numbers describe implementation cost and call counts; they do not predict production network
latency or substantiate the original report of tens of seconds. The benchmark job ran serially;
existing editor/server processes remained present, without dedicated hardware isolation.

Run the indexed benchmark and correctness assertions from the repository root:

```shell
mvn -f ravenroot/pom.xml -pl ravenroot-server -am \
  -Dtest=AuthoringReadIndexIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Docker must be available: the test starts `postgres:17-alpine` and fails if it cannot. Each index
uses four PostgreSQL connections with a five-second pool wait; production configuration retains
its existing defaults (ten connections and a ten-second pool wait). GitHub fixture pages contain
50 summaries; GitLab pages contain two. The 10/100/1000 datasets contain unique canonical draft
documents of approximately 600 bytes, zero release documents and zero publications. A separate
`AuthoringPublicationIndexTest` verifies catalog-only publication/unpublication, one fresh catalog
validation per unchanged first page, and no provider-body reload. The established 200-publication
catalog cap remains in force; the 1000-document fixture does not claim 1000 admitted publications.

The baseline was captured against production revision
`f1c24ec3ce6165a109f1754cb4681d66a35e59a1`, using the seeded provider fixtures and
`AuthoringReadBaselineTest` from this change. To repeat it, use a separate checkout at that revision,
copy only `AuthoringReadBaselineTest.java`, `GithubAuthoringRepositoryTest.java` and
`GitlabAuthoringRepositoryTest.java` from this change into their matching test paths, then run the
same command with `-Dtest=AuthoringReadBaselineTest`. The baseline invocation ran that single
benchmark test. It was not a full baseline test-suite run. Running that harness on the new production
source measures the new implementation instead.

Warm/restart latency uses ten samples per operation; p50/p95 use nearest-rank samples 5/10.
Cold initialization and one-document update each have one sample. Concurrent runs record one
list/open/history operation per reader, separately at 1/10/50 readers. The artifact contains all
samples' aggregate percentiles and call counts. Process CPU is measured with the JVM operating-system
bean; heap is used-heap change across a case, can decrease after GC, and is not allocation or peak
RSS. The `retained_bytes` counter measures cached SQL content. The separate `index_storage_bytes`
measurement includes the live SQLite DB/WAL/SHM files without forcing a checkpoint, or PostgreSQL
table/index/TOAST relation sizes for all authoring tables in the fixture schema. Storage is sampled
after warm/concurrent verification while the index remains open. `process_rss_bytes` records the
instantaneous test-JVM resident set at that point using `ps`; it is not a peak or per-operation cost.
Cold-row CPU/heap deltas include the case's warm/concurrent verification; cold wall time and provider
calls stop at its first returned page. Additional tests cover mixed writes, isolated tenants,
independent PostgreSQL instances, restart, cancellation and interrupted synchronization.

| Database/provider | Drafts | Cold first page ms | Warm list p50/p95 ms | One-document ms | New bodies |
| --- | ---: | ---: | ---: | ---: | ---: |
| sqlite/github | 10 | 58.492 | 1.445/1.802 | 7.147 | 1 |
| sqlite/github | 100 | 91.417 | 1.328/1.835 | 9.542 | 1 |
| sqlite/github | 1000 | 904.447 | 1.411/3.188 | 23.380 | 1 |
| sqlite/gitlab | 10 | 21.006 | 0.970/1.221 | 6.899 | 1 |
| sqlite/gitlab | 100 | 82.034 | 1.128/1.550 | 9.291 | 1 |
| sqlite/gitlab | 1000 | 786.750 | 0.994/1.379 | 30.520 | 1 |
| postgresql/github | 10 | 53.286 | 2.426/3.549 | 9.655 | 1 |
| postgresql/github | 100 | 160.684 | 2.282/2.519 | 14.108 | 1 |
| postgresql/github | 1000 | 1537.878 | 2.619/4.439 | 45.227 | 1 |
| postgresql/gitlab | 10 | 40.093 | 2.219/2.532 | 7.544 | 1 |
| postgresql/gitlab | 100 | 150.355 | 1.929/2.101 | 11.284 | 1 |
| postgresql/gitlab | 1000 | 1430.873 | 1.835/2.000 | 44.053 | 1 |

Every unchanged indexed first page made three provider calls (repository authorization and two
refs) and downloaded zero document bodies at every size and backend. At 1000 drafts, the original
GitHub first-page list used 509 provider calls and 50 bodies per request (p50/p95 179.897/237.321 ms);
the GitLab fixture used 55 calls and two bodies (40.314/43.824 ms). Cold indexed builds deliberately
validate the complete inventory, rather than only the first page: 1010 GitHub calls or 1018 GitLab
calls and 1000 bodies in this fixture. A one-document change downloaded exactly one body. Restart
reused all persisted content and required three validation calls for the first indexed list page.


At 1000 drafts, measured storage and instantaneous process RSS were:

| Database/provider | Cached SQL content bytes | Complete index bytes | Test-JVM RSS bytes |
| --- | ---: | ---: | ---: |
| sqlite/github | 606780 | 1515520 | 407453696 |
| sqlite/gitlab | 606780 | 1519616 | 424787968 |
| postgresql/github | 606780 | 1859584 | 418906112 |
| postgresql/gitlab | 606780 | 1851392 | 398082048 |

The separate publication workload contains 20 drafts, 20 released documents and 20 verified
publications, measured for both providers with both databases. Ten unchanged warm or restart lists
made 30 provider calls and ten catalog calls, with zero provider document-body downloads. The first
page immediately after reopening also asserted three provider calls, one catalog call and zero
bodies. Unpublishing through a catalog-only change required one catalog validation and zero provider
bodies. The artifact records those list latencies and physical index sizes separately from the
zero-publication scaling dataset. No live-provider measurements were run.
