# Build, local harness, and test strategy

Build the complete distribution with JDK 21, Maven, Node.js 20.19 or later, and npm, then verify contracts at their natural boundaries.

## Required practice

- Run `./ravenroot/scripts/build-release.sh` to assemble server, CLI, UI, and distribution artifacts.
- Use unit tests for parsers and state transitions, contract tests for SPIs and transports, integration tests for engine and persistence boundaries, and end-to-end tests for packaged launch.
- Exercise both Pekko and any installed Akka adapter, Test and Run modes, restart recovery, authentication refusal, and extension absence.
- Reproduce UI behavior against the live node catalog rather than a separately maintained palette fixture.

Use the complete [command-line tools reference](../reference/command-line-tools.md) for `dev.sh`,
`service.sh`, `plugin.sh`, and the application CLI. Bundle-reference changes begin in the extension
module README and are published with `python3 scripts/publish_bundle_reference.py`; check mode is a
required drift gate.

## Tests that need a database

The shared PostgreSQL adapter's conformance suite starts a real PostgreSQL server in a container, so
running `ravenroot-persistence-postgresql` needs a working Docker daemon. The container is started by
the test code rather than by a workflow, so a developer and continuous integration provision it the
same way, and an absent daemon fails the run rather than silently skipping the assertions the adapter
exists to prove.

## Boundary

A change is complete when its machine contract, executable test, and user-facing English documentation agree. Build success alone does not establish compatibility.

## References

- [Related contract](../reference/api-cli.md)
- [Related guide](api-doc-release.md)
