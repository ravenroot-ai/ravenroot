# Application, HTTP, SSE, and CLI integration

Choose the narrowest transport while preserving the same execution and authority contract.

## Integration sequence

1. Use the transport-neutral application API when Ravenroot shares the host process; do not introduce HTTP solely for internal composition.
2. For remote use, inspect GraphML first, submit with explicit `mode`, retain the HTTP 202 execution ID, and poll the execution resource to terminal state.
3. Open SSE for live changes and persist the last processed cursor; after reconnect, request recent events and reconcile any declared retention gap.
4. Use the CLI for operator and shell workflows, not as a way around authentication or ownership.

## Authority boundary

The host decides how caller identity maps to the application or HTTP boundary. Ravenroot remains authoritative for resource ownership, mode semantics, and state transitions.

## Linked contracts

- [Primary interface](../reference/api-cli.md)
- [Operational or security model](../reference/execution-events.md)
