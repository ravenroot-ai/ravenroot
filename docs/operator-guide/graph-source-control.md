# Graph source control and published deployments

Ravenroot keeps local-file authoring as its default. Set `RAVENROOT_GRAPH_AUTHORING_MODE=GIT` to let the workspace use a server-configured Git repository. The browser never chooses a repository, path, API endpoint, branch, credential, or tenant namespace. It receives only the operations and display metadata that the server enables.

## Configure the source archive

Git mode currently supports GitHub and GitHub Enterprise through a provider-neutral application port. Configure these values on the server:

| Setting | Purpose |
|---|---|
| `RAVENROOT_GRAPH_AUTHORING_MODE` | Startup enum `LOCAL` or `GIT`. Unset defaults to `LOCAL`, which ignores the remaining Git authoring and artifact bindings. |
| `RAVENROOT_GRAPH_AUTHORING_PROVIDER` | Git-mode provider enum. Unset defaults to `GITHUB`, currently the only accepted value. |
| `RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER` | Required GitHub account or organization. It accepts 1–100 ASCII letters, digits, `_`, `.`, or `-`. |
| `RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME` | Required operator-owned repository name with the same 1–100 character boundary. |
| `RAVENROOT_GRAPH_AUTHORING_GRAPH_DIRECTORY` | Confined repository-relative graph root, default `graphs`, with 1–512 allowed path characters and no absolute, trailing, doubled, or `..` segment. |
| `RAVENROOT_GRAPH_AUTHORING_DRAFT_BRANCH` | Tenant-draft branch prefix, default `draft`, with 1–200 allowed branch characters. It must differ from the release branch. |
| `RAVENROOT_GRAPH_AUTHORING_RELEASE_BRANCH` | Reviewed graph release branch, default `main`, with 1–200 allowed branch characters. It must differ from the draft prefix. |
| `RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES` | Required comma-separated grants such as `tenant-a=customers/a,tenant-b=customers/b`. Namespaces are confined; duplicate tenants and colliding derived draft branches are refused, and an absent tenant has no repository access. |
| `RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE` | Absolute HTTPS API origin/path, default `https://api.github.com`. An Enterprise prefix such as `/api/v3` is retained; credentials, query, and fragment are refused. |
| `RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE` | Required `APP` or `PAT`. `PAT` resolves a server-side token; `APP` mints a repository-scoped installation token. |
| `RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE` | Required opaque server-side credential name resolving the PAT or App private key. It is never caller-selected or returned to the browser or logs. |
| `RAVENROOT_GRAPH_AUTHORING_GITHUB_APP_ID` | Required only for `APP`; positive decimal App identifier of 1–20 digits. It is an identifier, not a credential. |
| `RAVENROOT_GRAPH_AUTHORING_GITHUB_INSTALLATION_ID` | Required only for `APP`; positive decimal installation identifier of 1–20 digits. The installation token is restricted to the configured repository. |
| `RAVENROOT_GRAPH_ARTIFACT_BASE_URL` | Required absolute HTTPS immutable-artifact base ending in `/`, without credentials, query, or fragment. Outbound access is confined to this origin. |
| `RAVENROOT_GRAPH_ARTIFACT_CATALOG_PATH` | Confined relative immutable catalog snapshot path, default `catalog.json`; absolute, trailing, doubled, or `..` paths are refused. |
| `RAVENROOT_GRAPH_AUTHORING_MAX_DOCUMENT_BYTES` | GraphML request, Git blob, and downloaded artifact ceiling. Default `10485760`; accepted Git-mode range 1–`104857600` bytes. |
| `RAVENROOT_GRAPH_AUTHORING_PAGE_SIZE` | Document and history page size. Default `50`; accepted range 1–200. |
| `RAVENROOT_GRAPH_AUTHORING_REQUEST_TIMEOUT_MILLIS` | Positive connect, request, and complete response-body deadline. Default `15000`; maximum `120000` milliseconds. |
| `RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT` | Additional retries for eligible GitHub `GET` failures. Default `1`; accepted range 0–3. Mutations are never retried blindly. |

For a GitHub App, also set the App and installation IDs. Store the PKCS#8/PKCS#1 private key at the credential reference. The adapter mints a short-lived installation token scoped to the configured repository with contents, pull-request, and metadata permissions. A PAT is resolved through the same server-only credential chain. The Helm chart uses the fixed reference `git-authoring` and reads its encoded environment binding from a Kubernetes Secret.

`ravenroot.graph.inspect` reads source. `ravenroot.graph.write` saves, restores, discards, or deletes drafts. `ravenroot.graph.release` proposes review in the graph repository. `ravenroot.graph.deploy.published` registers a verified publication. Tenant ownership checks still apply after scope and role checks; graph scopes alone never grant another tenant's configured namespace.

## Version and release behavior

Every saved document carries graph-scoped `ravenroot.authoring.graphId` and `ravenroot.authoring.releaseVersion` metadata. The stable graph ID is separate from a runtime content digest. The positive authored release version is separate from GraphML schema versions and runtime aggregate versions. It is a canonical decimal integer from 1 through `9007199254740991`; leading zeroes, signs, fractions, exponents, and larger values are refused consistently by the browser, server, and publisher.

Opening or saving unchanged content does not increment the authored version. The first changed save after the release branch contains the previous draft increments it once; later saves on that draft keep the incremented value. Every mutation checks the observed draft ref, release ref, and publication token, then advances the draft ref with a non-force expected-parent update. A stale browser gets `409` and must reopen before retrying.

A release action creates or reuses a pull request from the tenant draft branch to the configured graph release branch. It grants no authority over Ravenroot's product repository or `main` branch. Deleting a draft removes the branch file while Git history remains the archive.

## Publish and deploy

Release CI must validate the exact release-branch GraphML through `/v1/graphs/inspect?purpose=LOCAL_DEPLOYMENT`, produce an external digest manifest, and upload GraphML, manifest, and catalog snapshot with conditional create semantics. The example at `docs/examples/graph-authoring` supports immutable HTTPS `PUT` and S3-compatible object storage. Each snapshot retains prior releases, binds releases to source commits, rejects reused or decreasing versions, verifies bytes on an idempotent retry, and records the node-package identities and active tenant program artifacts that the runtime actually resolved during admission.

The server downloads the configured catalog, manifest, and GraphML through its bounded outbound policy. It verifies source-commit binding, both SHA-256 digests, the manifest contract, tenant, authored identity, compatibility admission, and GraphML metadata. Before publication and again before import or deployment, every referenced node package must be installed with the same identity. Every `program` node's exact source must already have an active tenant-scoped artifact compatible with the current program runtime. Operators provision those program artifacts through the existing governed program-artifact lifecycle; publication does not compile, activate, or execute code. Resolution uses GraphML `attr.name` metadata, so arbitrary GraphML key IDs do not weaken the binding.

Current runtime admission runs again when `POST /v1/graph-artifacts/{graphId}/{version}/import` pins the bytes in the tenant-scoped immutable `GraphDefinitionStore`. Git mode refuses to start without that store. The request binds the catalog selection by both the GraphML digest and immutable manifest-and-graph artifact reference, so a changed catalog cannot substitute another release after selection. A separate `.../deploy` request resolves and verifies that exact pinned digest before registering a stopped deployment, and returns the digest of the bytes that were registered. Running it remains a separate lifecycle command, and Git branches are never executable. Selecting an older published version through **File → Published versions** imports and registers those retained immutable bytes for rollback without stopping an existing deployment.

Audit events record request ID, hashed tenant and subject, operation, resource, and outcome. They exclude GraphML, tokens, repository credentials, and provider response bodies.

## Recovery

For an uncertain save or release response, retry with the same idempotency key. Keys are bounded to tenant, subject, operation, and request fingerprint. The server reconciles Git commits by its fingerprint marker and pull requests by exact head and base SHAs. A different request using the same key is refused.

Back up the graph repository and immutable artifact store through their normal provider controls. Ravenroot's runtime database does not become a second draft archive. After restoring Ravenroot, configure the same repository, tenant mappings, and immutable catalog snapshot; the UI derives draft, release, and publication state from those authorities again.
