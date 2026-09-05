# Integrator guide

Embed Ravenroot, call its APIs, and add adapters without crossing application and operator boundaries.

## Reading path

- [Application, HTTP, SSE, and CLI integration](application-http.md) — Choose a transport, submit an execution, correlate identifiers, and consume ordered events.
- [Embedded-viewer protocol](embed-protocol.md) — Implement registration, launch, token exchange, projection retrieval, and revocation handling.
- [Nodes, plugins, and runtime adapters](extensions-adapters.md) — Package node behavior and adapter capabilities with fail-closed discovery and compatibility metadata.
- [First-party extension dependency pack](extension-pack.md) — Resolve every maintained node package with one Maven dependency, then activate only the packages the deployment trusts.
- [Model, agent, and program integration](ai-programs.md) — Connect model providers, bounded agent tools, and sandboxed artifacts through operator-owned profiles.

## Authority boundary

Integrators implement transport and extension boundaries but do not decide who may use them. Deployment policy and resource ownership remain enforced by the operator plane.
