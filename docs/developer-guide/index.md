# Developer guide

Build, test, extend, and document Ravenroot while preserving its public contracts.

## Reading path

- [Build, local harness, and test strategy](build-test.md) — Use the supported toolchains, run layered tests, and reproduce failures locally.
- [Extension and adapter development](extension-development.md) — Implement nodes, model providers, engines, persistence, and plugin packages against narrow SPIs.
- [API, documentation, and release discipline](api-doc-release.md) — Change machine and prose contracts together, verify compatibility, and produce auditable releases.

## Authority boundary

Contributors may change implementation only with its tests and public contracts. Release authority, secret material, and production operation remain outside development workflows.
