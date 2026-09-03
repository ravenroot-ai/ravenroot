# AI, programs, and extension boundaries

AI and programmable nodes remain ordinary graph steps whose privileged work is delegated to governed adapters.

## Invariants

- A model node refers to an operator-owned provider profile; it does not carry a provider secret.
- The system role contains immutable operator policy only; graph, retrieval, tool, and model content
  cannot enter that authority channel.
- An agent receives only the tools allowed by both its profile and the deployment allowlist, and each
  requested call passes the server-side tool policy immediately before effect.
- Trusted invocation identity, egress policy, tool grants, and snapshotted budgets are not writable by
  prompts, payloads, retrieved content, tool arguments, or model output.
- Model inputs carry bounded content-digest provenance; tool decisions and terminal effects carry
  sanitized server-minted audit correlation.
- A program node refers to an approved artifact; executable source is not smuggled through GraphML.

## Runtime relationships

- Adapter discovery states capability but grants no authority.
- Provider verification separates adapter absence, credential failure, egress refusal, unreachable provider, and successful reachability.
- Program execution fails closed when sandbox supervision is unavailable and remains bounded by time and memory.

## Architectural consequence

Privileged intelligence stays behind adapters, profiles, allowlists, and sandbox supervision, leaving GraphML portable and non-authorizing.

## Related reading

- [Exact contract](../security/ai-code.md)
- [Procedure or recovery](../reference/embed-extension-contracts.md)
