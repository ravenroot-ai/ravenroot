# Nodes, plugins, and runtime adapters

Add behavior through narrow packages whose capabilities are discoverable and whose absence fails closed.

## Integration sequence

1. Give the package a stable identity, version, compatibility declaration, and capability list.
2. Implement only the relevant node, model, agent, engine, persistence, or connector SPI; keep host secrets and policy outside the implementation contract.
3. Return classified failures instead of throwing transport- or vendor-specific details across the boundary.
4. Install into an isolated deployment, inspect `/v1/runtime` and `/v1/node-types`, then exercise the package through Test and a bounded Run.

## Authority boundary

An extension advertises behavior; it never self-grants egress, credentials, tools, deployment, or artifact approval. Operators retain installation and configuration authority.

## Linked contracts

- [First-party extension dependency pack](extension-pack.md)
- [Primary interface](../architecture/ai-extension-boundaries.md)
- [Operational or security model](../troubleshooting/ai-extensions.md)
