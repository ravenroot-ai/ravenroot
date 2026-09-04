# Extension and adapter development

Implement extension points behind narrow SPIs so the core runtime can classify capability and failure without vendor coupling.

## Required practice

- Choose one boundary: node behavior, model provider, agent runtime, engine, persistence, connector, or program supervisor.
- Declare stable package identity, version, compatible core range, and capabilities; make discovery side-effect free.
- Translate vendor errors into Ravenroot failure reasons, respect cancellation and budgets, and never log secret or raw token material.
- Ship contract tests for absence, incompatibility, refusal, success, timeout, and restart behavior.

## Boundary

Extension code can report a capability but cannot authorize its use. Tests must prove that credentials, egress, tools, and deployment remain governed by the host.

## Node-package conformance

Every node package should include a test that extends `NodeBehaviorContract` from
`ravenroot-api-testkit` and returns the package under test. The contract derives its checks from each
behavior descriptor, so extension authors do not duplicate the runtime's list of special properties.

If a behavior declares a deployment-owned adapter with `NodePropertyDescriptor.adapterId(...)`, a
blank value means “not configured yet.” The behavior must still build an action so the graph remains
admissible. If traversal reaches that action, it must return an exceptionally completed stage and
must not return a `NodeResult`. The conformance contract tests every declared adapter-binding
property independently, including ordinary and non-ASCII Java whitespace. Keep package-specific
tests for the refusal's classified error and for configured success; the shared contract does not
replace those behavioral assertions.

## References

- [Related contract](../integrator-guide/extensions-adapters.md)
- [Related guide](../security/ai-code.md)
