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
