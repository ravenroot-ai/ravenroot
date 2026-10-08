# Graph source control and published deployments

Ravenroot keeps local-file authoring as its default. Set `RAVENROOT_GRAPH_AUTHORING_MODE=GIT` to let the workspace use a server-configured Git repository. The browser never chooses a repository, path, API endpoint, branch, credential, or tenant namespace. It receives only the operations and display metadata that the server enables.

## Configure the source archive

Git mode currently supports GitHub and GitHub Enterprise through a provider-neutral application port. Configure these values on the server:

| Setting | Purpose |
|---|---|
| `RAVENROOT_GRAPH_AUTHORING_PROVIDER=GITHUB` | Selects the installed provider adapter. |
| `RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER`, `..._NAME` | Select the operator-owned graph repository. |
| `RAVENROOT_GRAPH_AUTHORING_GRAPH_DIRECTORY` | Confines every graph path under one directory. |
| `RAVENROOT_GRAPH_AUTHORING_DRAFT_BRANCH`, `..._RELEASE_BRANCH` | Name the draft prefix and reviewed release branch. Each tenant receives a separate draft branch. |
| `RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES` | Comma-separated trusted mappings such as `tenant-a=customers/a,tenant-b=customers/b`. A tenant without a mapping is denied. |
| `RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE` | HTTPS API base, including an Enterprise prefix such as `/api/v3`. |
| `RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE` | `APP` or `PAT`. |
| `RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE` | Opaque server-side credential reference. It is never returned to the browser or logs. |
| `RAVENROOT_GRAPH_ARTIFACT_BASE_URL`, `..._CATALOG_PATH` | HTTPS location of one immutable catalog snapshot produced by release CI. |

For a GitHub App, also set the App and installation IDs. Store the PKCS#8/PKCS#1 private key at the credential reference. The adapter mints a short-lived installation token scoped to the configured repository with contents, pull-request, and metadata permissions. A PAT is resolved through the same server-only credential chain. The Helm chart uses the fixed reference `git-authoring` and reads its encoded environment binding from a Kubernetes Secret.

`ravenroot.graph.inspect` reads source. `ravenroot.graph.write` saves, restores, discards, or deletes drafts. `ravenroot.graph.release` proposes review in the graph repository. `ravenroot.graph.deploy.published` registers a verified publication. Tenant ownership checks still apply after scope and role checks; graph scopes alone never grant another tenant's configured namespace.

## Version and release behavior

Every saved document carries graph-scoped `ravenroot.authoring.graphId` and `ravenroot.authoring.releaseVersion` metadata. The stable graph ID is separate from a runtime content digest. The positive authored release version is separate from GraphML schema versions and runtime aggregate versions.

Opening or saving unchanged content does not increment the authored version. The first changed save after the release branch contains the previous draft increments it once; later saves on that draft keep the incremented value. Every mutation checks the observed draft ref, release ref, and publication token, then advances the draft ref with a non-force expected-parent update. A stale browser gets `409` and must reopen before retrying.

A release action creates or reuses a pull request from the tenant draft branch to the configured graph release branch. It grants no authority over Ravenroot's product repository or `main` branch. Deleting a draft removes the branch file while Git history remains the archive.

## Publish and deploy

Release CI must validate the exact release-branch GraphML through `/v1/graphs/inspect?purpose=LOCAL_DEPLOYMENT`, produce an external digest manifest, and upload GraphML, manifest, and catalog snapshot with conditional create semantics. The example at `docs/examples/graph-authoring` supports immutable HTTPS `PUT` and S3-compatible object storage. Each snapshot retains prior releases, binds releases to source commits, rejects reused or decreasing versions, verifies bytes on an idempotent retry, and derives compatibility and dependency evidence from the admitted document.

The server downloads the configured catalog, manifest, and GraphML through its bounded outbound policy. It verifies source-commit binding, both SHA-256 digests, the manifest contract, tenant, authored identity, compatibility admission, and GraphML metadata. Current runtime admission runs again when `POST /v1/graph-artifacts/{graphId}/{version}/import` pins the bytes in the tenant-scoped immutable `GraphDefinitionStore`. Git mode refuses to start without that store. A separate `.../deploy` request resolves and verifies the pinned authored identity before registering a stopped deployment. Running it remains a separate lifecycle command, and Git branches are never executable. Selecting an older published version through **File → Published versions** imports and registers those retained immutable bytes for rollback without stopping an existing deployment.

Audit events record request ID, hashed tenant and subject, operation, resource, and outcome. They exclude GraphML, tokens, repository credentials, and provider response bodies.

## Recovery

For an uncertain save or release response, retry with the same idempotency key. Keys are bounded to tenant, subject, operation, and request fingerprint. The server reconciles Git commits by its fingerprint marker and pull requests by exact head and base SHAs. A different request using the same key is refused.

Back up the graph repository and immutable artifact store through their normal provider controls. Ravenroot's runtime database does not become a second draft archive. After restoring Ravenroot, configure the same repository, tenant mappings, and immutable catalog snapshot; the UI derives draft, release, and publication state from those authorities again.
