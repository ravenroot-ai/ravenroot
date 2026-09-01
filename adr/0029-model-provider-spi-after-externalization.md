# ADR 0029: The model-provider SPI after AI-node externalization

- Status: Accepted
- Date: 2026-08-29
- Supersedes: The default-core AI and agent node placement in ADR 0005
- Superseded by: None
- Public references: [AI and extension boundaries](../docs/architecture/ai-extension-boundaries.md), [model, agent, and program integration](../docs/integrator-guide/ai-programs.md), [nodes, plugins, and runtime adapters](../docs/integrator-guide/extensions-adapters.md)

## Context

AI and agent nodes moved from the core to optional bundles. Their former scaffolding included public
embedding interfaces, registries, server configuration, release-boundary checks, and development
adapters. Removing or repurposing those pieces implicitly would change public contracts and could
make release checks vacuous.

## Decision

`ModelProvider` and `AgentRuntime` remain public embedding SPIs for applications that compose their
own behavior environment. They are not the extension surface for plugin bundles. A bundle implements
node behavior and reaches operator-granted services through the managed node-package channel.

The default server model-provider configuration plane is removed because the released composition
does not arm those core nodes. Release checks retain a non-vacuous anchor for legacy SPI routes and
also inspect bundle capability declarations. The default artifacts and image include no enabled
generative implementation.

## Consequences

- Existing embedders keep the SPI while the default product no longer advertises an unused provider
  configuration surface.
- Bundles receive only explicitly granted managed services and do not gain a credential or network
  channel through the embedding SPI.
- ADR 0005 remains historical for governance principles but no longer governs core placement of AI
  and agent nodes.
