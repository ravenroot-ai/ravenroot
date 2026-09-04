# Model, agent, and program integration

Connect AI and code execution through governed profiles rather than embedding provider or source details in GraphML.

## Integration sequence

1. Register the model or agent adapter and expose only its declared capabilities.
2. Create a provider profile containing adapter, endpoint, model, credential mode, and credential reference; verify it and inspect the classified outcome.
3. For agents, grant `outbound-http`, `tool-authorization`, and `agent-resources`; intersect discovered
   tools with the operator allowlist, and keep tool results inside payload budgets. Without the
   resource grant the first-party agent refuses before model egress.
4. Configure the deployment tool policy. Every model-requested built-in or MCP call is evaluated with
   trusted invocation identity and bounded canonical arguments immediately before effect; no policy
   or approval service means deny.
5. For programs, create an artifact, validate, test, approve, and activate it before a graph can refer to the active identity.

## Authority boundary

The integrator implements adapters and supervisors. Operators select profiles, credentials, egress,
tools, sandbox budgets, and approval roles. Graph authors select only approved references. Prompts,
retrieval, tool results, and model output never alter those choices; they carry digest provenance and
remain untrusted data.

Agent authority and spend are process-rooted and durable. Every model turn reserves finite input,
output, elapsed-time, and cost ceilings before egress; missing or invalid provider usage is charged at
the full reservation. Tool proposals and turns are non-refundable once dispatched. Child grants must
be strict subsets of their parent authority or finite ceilings, and cancellation releases active
team slots without refunding cumulative fan-out.

## Linked contracts

- [Primary interface](../reference/embed-extension-contracts.md)
- [Operational or security model](../security/ai-code.md)
