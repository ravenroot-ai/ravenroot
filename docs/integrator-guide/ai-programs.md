# Model, agent, and program integration

Connect AI and code execution through governed profiles rather than embedding provider or source details in GraphML.

## Integration sequence

1. Register the model or agent adapter and expose only its declared capabilities.
2. Create a provider profile containing adapter, endpoint, model, credential mode, and credential reference; verify it and inspect the classified outcome.
3. For agents, intersect requested tools with the operator allowlist and keep tool results inside payload budgets.
4. For programs, create an artifact, validate, test, approve, and activate it before a graph can refer to the active identity.

## Authority boundary

The integrator implements adapters and supervisors. Operators select profiles, credentials, egress, tools, sandbox budgets, and approval roles. Graph authors select only approved references.

## Linked contracts

- [Primary interface](../reference/embed-extension-contracts.md)
- [Operational or security model](../security/ai-code.md)
