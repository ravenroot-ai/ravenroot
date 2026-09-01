# ADR 0005: Programmable, AI, and agentic nodes

- Status: Superseded in part
- Date: 2026-07-21
- Supersedes: Ungoverned execution of user-authored programs or model calls
- Superseded by: [ADR 0029](0029-model-provider-spi-after-externalization.md) for default-distribution AI and agent nodes
- Public references: [AI and extension boundaries](../docs/architecture/ai-extension-boundaries.md), [nodes, plugins, and runtime adapters](../docs/integrator-guide/extensions-adapters.md), [AI, tools, and programmable code](../docs/security/ai-code.md)

## Context

Graphs need useful integrations and programmable behavior without turning arbitrary code, model
calls, or tools into an execution backdoor. Node capability must remain inspectable and governed by
the product rather than hidden in UI conventions.

## Decision

Ravenroot separates graph description, trusted catalog declaration, and runtime implementation.
Interpreted capabilities are discoverable, validated, policy-controlled, observable, and versioned.
Credentials are referenced rather than embedded in graph content. Program artifacts have an
explicit lifecycle and are admitted through policy before execution. Missing providers or denied
tools fail explicitly.

Programmable implementations and integrations may be supplied through node packages and plugin
bundles. AI and agent node implementations are not part of the default core or release image; ADR
0029 governs the remaining embedding SPI and the external bundle route.

## Consequences

- Graphs remain inspectable programs whose unknown properties survive round trips.
- A node implementation cannot gain network, credential, or tool authority merely by being present.
- The original placement of AI and agent nodes in the core is historical; the governance principles
  remain in force for external bundles.
