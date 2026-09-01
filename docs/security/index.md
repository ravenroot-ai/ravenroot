# Security guide

Apply trust boundaries and controls for identity, untrusted graphs, secrets, code, AI, and embeds.

## Reading path

- [Threat model, identity, and authorization](trust-identity.md) — Map principals and resources, then enforce authentication and ownership at every control plane.
- [Graph input, credentials, and egress](input-secrets-egress.md) — Treat GraphML and payloads as untrusted while keeping secret material and network rights server-side.
- [AI, tools, and programmable code](ai-code.md) — Bound model calls, agent tools, and program artifacts with explicit adapters, policy, and sandbox supervision.
- [Embed, privacy, and audit](embed-privacy.md) — Require operator attestations, minimize projections, and retain evidence for access and revocation.

## Authority boundary

Security pages state the enforced control and the responsibility that remains with operators or hosts. They do not treat a configured boundary as an absolute guarantee.
