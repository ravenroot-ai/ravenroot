# AI, tools, and programmable code

Models, agents, and program artifacts increase capability only through explicitly installed and configured enforcement points.

## Controls

- Bind model calls to provider profiles and write-only credential references; classify verification before enabling Run.
- Give an agent the intersection of profile tools and deployment allowlists, never arbitrary host functions.
- Keep artifact source in governed artifact storage, require validation and approval, and execute only an active identity.
- Enforce the sandbox supervisor, 5,000 ms default timeout, 64 MiB default heap, and dual control enabled by default.

## Residual responsibility

Model output is untrusted payload, tool use is a privileged side effect, and code approval is distinct from code execution. Adapter absence and supervisor absence both fail closed.

## Application

- [Definitions and limits](../architecture/ai-extension-boundaries.md)
- [Operator procedure or recovery](../troubleshooting/ai-extensions.md)
