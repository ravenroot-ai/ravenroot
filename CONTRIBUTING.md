# Contributing to Ravenroot

Ravenroot welcomes focused code, documentation, tests, examples, and design improvements. Public
contributions become part of the product contract, so they must be reviewable, reproducible, and safe
to publish.

## Start from the integration branch

`main` is the default branch because it represents the latest released product state and its public
documentation. Active development is integrated on `dev`.

1. Fork the repository and fetch the upstream `dev` branch.
2. Create a focused `feature/*`, `fix/*`, `docs/*`, or `test/*` branch from the current `dev`.
3. Make the change, its tests, its documentation, and any required change fragment together.
4. Push the topic branch to your fork and open a pull request against `ravenroot-ai/ravenroot:dev`.

Ordinary pull requests from forks or repository branches must target `dev`, not `main`. Pull requests
to `main` are reserved for the internal `dev` branch and protected internal `hotfix/*` branches. A
maintainer classifies each such pull request with exactly one of `release:none`, `release:patch`,
`release:minor`, or `release:major`. `release:none` is restricted to documentation and public content;
it never advances the product version, creates a tag, or publishes deliverables. Repository rules
restrict hotfix creation and updates to release maintainers, while branch protection and CODEOWNERS
review authorize the merge. If a pull request is opened against the wrong base, change its base branch
to `dev`; the contribution does not need to be recreated.

## Describe the public change

For a user-visible change, add one Markdown fragment under [`.changes/`](.changes/README.md). The
fragment records the release-note entry and whether the next release needs a patch or minor version
increment. Pure refactoring, tests, or editorial corrections may omit a fragment when the pull request
explains why no public release note is needed.

Pull requests should explain:

- the user or operator outcome;
- the compatibility and security impact;
- the tests and checks that were run;
- any validation that could not be run;
- the change fragment, or the reason one is not required.

Use the pull request template as the completion checklist. Keep commits focused and write commit
messages and pull request text in English.

## Preserve public contracts

- Change behavior, tests, examples, and public documentation as one reviewable unit.
- Do not silently break GraphML, HTTP, event, CLI, extension, persistence, or configuration contracts.
- Describe migrations, operator action, deprecations, and compatibility boundaries explicitly.
- Never include credentials, private paths, private repository references, personal data, or private
  project-management and operational material.
- Do not weaken a check, test, permission, or security control merely to make a change pass.

## Documentation contributions

Ravenroot documentation is a product manual for users, operators, integrators, and developers.

1. Select the audience from the [documentation home](docs/index.md).
2. Select the page type: tutorial, how-to, concept, reference, or runbook.
3. Update the one page that owns the contract and link to it from other contexts.
4. Update every affected public cross-reference in the same change.

English is the canonical public contract and the only language used in this repository. Describe
accepted product behavior directly; keep speculative options, progress tracking, and private decision
support outside the product manual. Commands and examples must use stable public names and contain no
private paths or credentials.

Documentation review checks product truth, completeness, security language, audience fit,
discoverability, terminology, links, and English-language consistency. Read the
[editorial guide](docs/editorial-guide.md) for the complete policy.

## How releases are prepared

Maintainers collect approved changes on `dev`. When that set is ready, a release pull request from
`dev` to `main` uses `release:patch`, `release:minor`, or `release:major`, updates the authoritative
version, changelog, and release notes, and passes the complete release gate. That pull request is
merged with a merge commit. The exact merge commit will be tagged and published by the release
workflow once that workflow is available. A documentation-only promotion may instead use
`release:none`; it passes the policy, documentation, and security gates without creating a release.

Read [Releasing Ravenroot](docs/governance/releasing.md) for the complete branch, version, hotfix, and
publication policy.
