# Extension and adapter development

Implement extension points behind narrow SPIs so the core runtime can classify capability and failure without vendor coupling.

## Required practice

- Choose one boundary: node behavior, model provider, agent runtime, engine, persistence, connector, or program supervisor.
- Declare stable package identity, version, compatible core range, and capabilities; make discovery side-effect free.
- Translate vendor errors into Ravenroot failure reasons, respect cancellation and budgets, and never log secret or raw token material.
- Ship contract tests for absence, incompatibility, refusal, success, timeout, and restart behavior.

## Boundary

Extension code can report a capability but cannot authorize its use. Tests must prove that credentials, egress, tools, and deployment remain governed by the host.

## References

- [Related contract](../integrator-guide/extensions-adapters.md)
- [Related guide](../security/ai-code.md)
