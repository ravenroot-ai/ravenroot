# AI, tools, and programmable code

Models, agents, and program artifacts increase capability only through explicitly installed and configured enforcement points.

## Controls

- Bind model calls to provider profiles and write-only credential references; classify verification before enabling Run.
- Put only operator policy in the model protocol's system role. Graph instructions, payloads,
  retrieved content, tool results, and model output remain structurally untrusted.
- Give an agent the intersection of profile tools and deployment allowlists, then authorize every
  model-requested call server-side immediately before effect. Missing authorization denies.
- Parse and canonicalize tool arguments under server bounds; derive tenant identity from the
  delivered security context, never from model arguments or retrieved content.
- Record bounded source-kind/digest provenance for model inputs and sanitized request/invocation/
  server-call correlation for tool refusals and effects. Do not retain prompt, argument, result,
  endpoint, or credential values in those records.
- Keep artifact source in governed artifact storage, require validation and approval, and execute only an active identity.
- Enforce the sandbox supervisor, 5,000 ms default timeout, 64 MiB default heap, and dual control enabled by default.

## Residual responsibility

Model output is untrusted payload, tool use is a privileged side effect, and code approval is distinct
from code execution. A `REQUIRE_APPROVAL` tool decision performs no effect until a durable approval
redemption mechanism exists. Adapter, authorization-service, and supervisor absence all fail closed.

## Application

- [Definitions and limits](../architecture/ai-extension-boundaries.md)
- [Operator procedure or recovery](../troubleshooting/ai-extensions.md)
