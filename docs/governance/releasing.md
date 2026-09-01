# Releasing Ravenroot

Ravenroot uses a simplified GitFlow model: `dev` integrates approved changes, while `main` contains
only released states. A release pull request is the explicit decision to publish a selected set of
changes.

## Branch roles

| Branch | Purpose | Accepted changes |
|---|---|---|
| `main` | Default branch and released product history | Release pull requests from the internal `dev` branch; exceptionally, protected internal `hotfix/*` pull requests |
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

Merging the release pull request is the publication authorization. A single protected release workflow
triggered by the resulting push to `main` must:

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

Tag creation and delivery happen in the same workflow. A maintainer does not create the tag manually,
so a released `main` commit cannot be silently skipped. The workflow must be concurrency-protected,
use minimum permissions, and be safe to rerun after a partial failure without replacing an immutable
artifact.

The publishing workflow is intentionally not present while this repository does not yet contain the
product source and authoritative build configuration. It must be implemented and made a required
release gate when those inputs arrive. Until then, this document defines the process but a merge to
`main` does not create deliverables.

## Synchronizing after a release

After a normal release, fast-forward `dev` to the released `main` merge commit before integrating more
work. This gives both branches the same release boundary without replaying commits.

For an urgent correction:

1. have a release maintainer create a protected internal `hotfix/*` branch from the current `main`;
2. prepare a patch-only release, including its tests, fragment, version, changelog, and release notes;
3. open the pull request to `main` and obtain the required CODEOWNERS approval;
4. pass the full release gate and branch-protection requirements;
5. merge with a merge commit so the normal `main` release workflow tags and publishes it;
6. merge the released `main` state back into `dev` immediately, resolving the correction once rather
   than recreating it independently.

No hotfix bypasses review, checks, version consistency, release notes, or publication verification.
