# Releasing Ravenroot

Ravenroot uses a simplified GitFlow model: `dev` integrates approved changes, while `main` contains
released product states and explicitly classified public-content updates. A release pull request is
the explicit decision to publish a selected set of product changes.

## Branch roles

| Branch | Purpose | Accepted changes |
|---|---|---|
| `main` | Default branch, released product history, and current public documentation | Release or content-promotion pull requests from the internal `dev` branch; exceptionally, protected internal `hotfix/*` pull requests |
| `dev` | Integration branch for the next release | Reviewed topic branches and pull requests from repository branches or forks |
| `feature/*`, `fix/*`, `docs/*`, `test/*` | Focused contribution branches based on `dev` | One bounded change returning to `dev` |
| `hotfix/*` | Exceptional urgent correction based on `main` | Patch release returning to `main`, followed by synchronization to `dev` |

`main` remains the repository's default branch so visitors, source archives, and ordinary clones see
the latest released state. Contributors select `dev` as the base of ordinary pull requests. Repository
rules must require pull requests and the relevant status checks on both protected branches.

The `main-source-policy` status check accepts a pull request to `main` only when its head repository is
exactly this repository and its head branch is either:

- the exact `dev` branch; or
- a `hotfix/*` branch.

The check runs from the base branch through `pull_request_target`, has no repository permissions, does
not check out either branch, calls no API, and executes no pull request code. A fork therefore cannot
replace or spoof the required check. The check must be configured as required on `main`. Fork and
ordinary topic pull requests target `dev`.

Source acceptance is not hotfix authorization. A repository ruleset targeting `hotfix/*` must restrict
branch creation and updates to release maintainers. Protection on `main` must require review and
CODEOWNERS approval before merge. Authorization is never derived from mutable pull request metadata.

## Classifying pull requests to `main`

Every pull request to `main` requires exactly one release-intent label. The CI classifier verifies the
label against the changed paths before any merge is allowed.

| Label | Meaning | CI tier | Version, tag, and deliverables |
|---|---|---|---|
| `release:none` | Documentation or public-content promotion only | Policy, documentation, and security checks | Unchanged; no tag or publication |
| `release:patch` | Backward-compatible correction | Complete release gate | Patch increment and publication |
| `release:minor` | Backward-compatible feature or incompatible `0.x` change | Complete release gate | Minor increment and publication |
| `release:major` | Stable-series incompatible change | Complete release gate | Major increment and publication |

`release:none` is deliberately fail-closed. Every changed path must be in the reviewed documentation
and public-content allowlist. Product source, build configuration, deployment configuration, workflow
automation, release metadata, and non-documentation change fragments make that label invalid. A
documentation-only pull request must use `release:none`; it must not consume a release number merely
to satisfy CI.

The labels classify the whole diff between `main` and the pull-request head. Consequently, a
content-only promotion is possible only when `dev` contains no unreleased product changes. If product
changes are already accumulated on `dev`, publish them through a correctly classified release first
or prepare the content update from the synchronized released state under the normal protected branch
rules.

## Integrating changes on `dev`

Topic branches start from the current `dev`. Each pull request contains the implementation,
verification, public documentation, and a [change fragment](../../.changes/README.md) when the result is
user-visible. Topic pull requests may be squash-merged to keep each contribution atomic.

Every public file, commit message, pull request, changelog entry, and release note is written in
English. Private paths, credentials, unpublished operational procedures, personal data, and
private project-management material are outside the public repository boundary.

## Preparing a release pull request

When the changes accumulated on `dev` form a coherent release, prepare a pull request from the
repository's `dev` branch to `main`. The release pull request:

1. selects the version from the highest-impact unconsumed change fragment;
2. updates the authoritative product version and every derived release surface consistently;
3. assembles and removes the consumed fragments;
4. updates the changelog and GitHub release notes, including compatibility boundaries, migrations,
   operator actions, and security notices;
5. contains no development-only version such as `SNAPSHOT` in a published coordinate;
6. passes the complete test, compatibility, packaging, documentation, and release-readiness gates;
7. proves that the requested version is greater than the latest release and that its tag does not
   already exist.

Merge the release pull request with a merge commit, not squash or rebase. This keeps `dev` as a parent
of the released commit and prevents already released integration commits from appearing as unrelated
work in the next release.

## Alpha semantic versioning

Ravenroot follows Semantic Versioning. Before 1.0, the release series uses the `alpha.1` prerelease
identifier and increments the core version according to the public impact:

- the first alpha is `0.1.0-alpha.1`;
- a release containing only backward-compatible fixes increments patch, for example
  `0.1.0-alpha.1` to `0.1.1-alpha.1`;
- a release containing a backward-compatible feature increments minor, for example
  `0.1.1-alpha.1` to `0.2.0-alpha.1`;
- an incompatible change during `0.x` increments minor and is called out prominently in the release
  notes with its migration path;
- the highest-impact change determines one increment for the whole release, regardless of the number
  of included issues or pull requests.

Release versions are never reused. The release tag is exactly `v<version>`, such as
`v0.2.0-alpha.1`, and identifies one immutable commit and one set of deliverables.

## Tagging and publishing

Merging a pull request labeled `release:patch`, `release:minor`, or `release:major` is the publication
authorization. A `release:none` merge is never publication authorization. A single protected release
workflow triggered by the resulting push to `main` must verify the merged pull request's release
intent and, for a version-changing release:

1. run the release gates again against the exact merge commit;
2. read and validate the authoritative product version;
3. require the version to be new, increasing, and consistent across release metadata;
4. build each deliverable once;
5. create the annotated `v<version>` tag for that exact commit;
6. publish the same built outputs to their registries and attach them to a GitHub Release;
7. publish checksums, signatures, a software bill of materials, provenance, and container digest where
   applicable;
8. mark alpha GitHub Releases as prereleases and verify that every publication channel resolves to the
   same version and commit.

Tag creation and delivery happen in one protected automation chain. A maintainer does not create the tag manually,
so a released `main` commit cannot be silently skipped. For `release:none`, the workflow records the
classification and exits successfully without changing the version, creating a tag, or building and
publishing deliverables. The workflow must be concurrency-protected, use minimum permissions, and be
safe to rerun after a partial failure without replacing an immutable artifact.

The publishing workflow is separate from CI classification and has no authority on pull requests or
ordinary branch pushes. A qualifying merge to `main` is checked against its merged pull request and
exact-commit CI run before automation creates an annotated tag. Because events created by the
repository `GITHUB_TOKEN` do not recursively start ordinary tag-push workflows, the authorization
workflow explicitly dispatches the publication workflow on that newly created tag ref. A manual
dispatch is only a recovery entry point: it is rejected unless the workflow itself is running on the
same existing tag ref.

## Release automation and operator runbook

### Publication boundary

Published Maven coordinates use group ID `ai.ravenroot`. The reviewed Central boundary contains:

- `ravenroot-parent`, `ravenroot-application-api`, `ravenroot-core`,
  `ravenroot-programming-graalvm`, `ravenroot-plugin-bundle`, `ravenroot-persistence-sqlite`,
  `ravenroot-pekko`, `ravenroot-observability-otel`, `ravenroot-server`, `ravenroot-cli`, and
  `ravenroot-distribution`;
- the reusable `ravenroot-api-testkit`, `ravenroot-engine-testkit`, and
  `ravenroot-persistence-testkit` conformance artifacts;
- the `ravenroot-extensions` parent and the `ravenroot-ai`, `ravenroot-amqp091`,
  `ravenroot-filesystem`, `ravenroot-jdbc`, `ravenroot-kafka`, `ravenroot-mail`,
  `ravenroot-object-storage`, `ravenroot-ocr`, `ravenroot-openapi-client`,
  `ravenroot-openapi-server`, `ravenroot-spel`, `ravenroot-telegram`, and
  `ravenroot-websocket` extensions.

The example node project, sandbox-supervisor conformance fixture, optional Akka build, development
harness, sample application, and out-of-reactor adapter builds are not Central publications. The OCI
image name is `ghcr.io/ravenroot-ai/ravenroot:<version>`. Prereleases never create or move `latest`.

### Protected environment and signing identity

The GitHub Environment is named `release`. It requires owner review and accepts only `v*` tag refs.
The active `Protect immutable release tags` tag ruleset allows authorized automation to create a new
`v*` ref but permits no actor to update or delete one after creation.
Its environment-scoped secrets are:

- `CENTRAL_USERNAME`
- `CENTRAL_TOKEN`
- `MAVEN_GPG_PRIVATE_KEY`
- `MAVEN_GPG_PASSPHRASE`

Do not create repository-level copies of these secrets. The release signing identity is
`Ravenroot Release Signing <releases@ravenroot.ai>` and its fingerprint is
`31841485DE6D55A504CAC4B2DB18FAA6B85083EA`. The key expires on 2028-09-01; begin rotation at least
90 days before expiry, publish the replacement key, update the protected secret, and land the new
public fingerprint through normal review before using it. The armored
[public release key](../security/release-signing-key.asc) is tracked in this repository and published
through `keyserver.ubuntu.com`.

Verify a downloaded Central artifact with:

```sh
gpg --keyserver keyserver.ubuntu.com --recv-keys 31841485DE6D55A504CAC4B2DB18FAA6B85083EA
gpg --fingerprint 31841485DE6D55A504CAC4B2DB18FAA6B85083EA
gpg --verify artifact.jar.asc artifact.jar
```

### Maintainer procedure

1. On `dev`, choose the version from the highest-impact unconsumed fragment. Update the root Maven
   version, every child POM, the UI package and lockfile versions, and both Helm version fields. Run
   `python3 scripts/check_product_version.py`.
2. Assemble the changelog and commit reviewed GitHub Release notes at
   `docs/releases/v<version>.md`; remove only the fragments consumed by that release.
3. Open the internal `dev` to `main` pull request. Apply exactly one of `release:patch`,
   `release:minor`, or `release:major`. The first `0.1.0-alpha.1` promotion uses `release:minor`.
   Never add an automated or post-merge version-bump commit.
4. Obtain the required review and merge with a merge commit. The resulting merge commit is the exact
   source that authorization validates and tags.
5. Wait for `authorize-release` to confirm the merged pull request, version transition, absence of a
   reused version, and the exact-commit `ci.yml` result. It then creates `v<version>` once and starts
   publication on that tag ref.
6. Review the pending `release` Environment deployment. Confirm the tag, commit, version, release
   notes, and hosted gates, then approve it.
7. After completion, verify the Maven coordinates on Central, pull the GHCR image by version and by
   reported digest without authentication, inspect its SBOM/provenance attestations, verify the
   GitHub prerelease assets against `SHA256SUMS`, and confirm the release points at the peeled tag
   commit.
8. Fast-forward `dev` to the released `main` merge commit before accepting more integration work.

### Retry and partial-publication recovery

Never delete, replace, or reuse a released version. Re-run **Publish immutable release** from the
existing tag ref and provide that same tag as the input. The workflow rebuilds deterministic payloads
from the tag, then follows these rules:

- if no Central component exists, it uploads and automatically publishes one complete signed bundle;
- if every Central component exists, it rebuilds locally without uploading and compares every immutable
  payload byte-for-byte with Central;
- if only part of the Central coordinate set is visible, or any payload differs, it stops for human
  investigation;
- if the GHCR version tag exists, its Linux AMD64 image-manifest digest must match the normalized
  rebuilt image and its SBOM and provenance attestation manifests must be present, or the retry stops;
  a matching image is retained without moving `latest`;
- existing GitHub Release assets are downloaded and hashed; matching assets are retained, missing
  assets are added, and mismatches stop the run.

If Central reports `PUBLISHING` or `VALIDATING`, wait for the portal deployment to reach a terminal
state before retrying. If one registry succeeded and another failed, preserve the successful
immutable publication and resume from the same tag. Open a focused public issue for a reproducible
pipeline defect; do not relax ancestry, version, review, signature, content-identity, or provenance
checks.

### First-release checklist

Before approving `v0.1.0-alpha.1`, confirm all of the following:

- the `ai.ravenroot` namespace remains verified and the four `release` Environment secrets have a
  current update timestamp;
- `docs/releases/v0.1.0-alpha.1.md`, the changelog, and all version surfaces were reviewed in the
  release pull request;
- the signing fingerprint above resolves from the public keyserver and matches the tracked key;
- no Maven coordinate, Git tag, GitHub Release, or GHCR version tag already uses this version;
- exact-commit hosted CI and the non-secret tag gates are green;
- the owner approves the Environment only after matching the proposed tag and commit;
- after publication, Central signatures, anonymous GHCR pulls by version and digest, attestations,
  checksums, and prerelease status are verified and recorded in the release pull request.

The implementation follows the official
[Central Publisher Portal Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/)
and [Central signing](https://central.sonatype.org/publish/requirements/gpg/) guidance, together with
GitHub's documentation for
[deployment environments](https://docs.github.com/actions/reference/deployments-and-environments),
[publishing container images](https://docs.github.com/actions/tutorials/publish-packages/publish-docker-images),
and [artifact attestations](https://docs.github.com/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).

## Synchronizing after a release

After a normal release or content-only promotion, fast-forward `dev` to the resulting `main` merge
commit before integrating more work. This gives both branches the same public boundary without
replaying commits.

For an urgent correction:

1. have a release maintainer create a protected internal `hotfix/*` branch from the current `main`;
2. prepare a patch-only release, including its tests, fragment, version, changelog, and release notes;
3. open the pull request to `main` and obtain the required CODEOWNERS approval;
4. pass the full release gate and branch-protection requirements;
5. merge with a merge commit so the normal `main` release workflow tags and publishes it;
6. merge the released `main` state back into `dev` immediately, resolving the correction once rather
   than recreating it independently.

No hotfix bypasses review, checks, version consistency, release notes, or publication verification.
