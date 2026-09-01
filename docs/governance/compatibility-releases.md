# Compatibility and release policy

Compatibility is evaluated independently for GraphML documents, HTTP resources, SSE events, CLI behavior, extension SPIs, persisted state, and documentation.

## Required practice

- A release states which GraphML profile and scalar/property vocabulary it accepts.
- HTTP and event evolution preserves documented meanings or introduces a version boundary; consumers ignore only fields declared extensible.
- CLI exit codes and command semantics are public automation contracts.
- Plugin and adapter packages declare compatible core ranges; incompatible code is refused during discovery.
- Storage changes carry an upgrade and recovery procedure; documentation changes ship with the contract they describe.

## Boundary

Release notes identify deliberate compatibility boundaries and operator actions. They do not weaken a stable contract through undocumented behavior.

## References

- [Related contract](../reference/graphml.md)
- [Related guide](../developer-guide/api-doc-release.md)
