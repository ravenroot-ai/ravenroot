# ADR 0017: Product boundaries for optional generative capabilities

- Status: Accepted
- Date: 2026-08-09
- Supersedes: An implicit boundary around model and agent adapters
- Superseded by: None; amended by [ADR 0029](0029-model-provider-spi-after-externalization.md)
- Public references: [AI and extension boundaries](../docs/architecture/ai-extension-boundaries.md), [model, agent, and program integration](../docs/integrator-guide/ai-programs.md), [AI, tools, and programmable code](../docs/security/ai-code.md)

## Context

The project needed a checkable product boundary between the default Ravenroot distribution and
optional model or agent capabilities. Product architecture, distribution contents, and who operates
a deployment are separate questions.

## Decision

The default distribution and default image contain no concrete model-provider or agent-runtime
implementation and no enabled generative node bundle. Optional implementations are separately
obtained, explicitly built and installed, and deliberately enabled by the operator. Ravenroot does
not operate a hosted public instance or SaaS service, and the project does not use optional AI nodes
for its own professional operations.

The default-artifact and default-image boundary is machine-checked for both legacy SPI
implementations and bundle capability declarations. ADR 0029 amends the route: AI nodes live outside
the core, while the model-provider SPI remains only as an embedding surface. The hosted-operation
and internal-professional-use boundaries are conduct decisions: repository controls do not detect
whether somebody operates a hosted instance or uses an optional AI node for project work.

## Consequences

- Installing the default product does not silently arm model or agent execution.
- An operator's separately built bundle is that operator's extension, not part of the default
  Ravenroot artifact.
- Only exclusion from default artifacts and bundles is machine-checked; hosted operation and
  internal use require organizational compliance rather than a repository gate.
- Changing hosted-operation or default-distribution boundaries requires a new explicit decision;
  this ADR does not make a legal conclusion for integrators.
