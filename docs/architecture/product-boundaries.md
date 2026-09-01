# Product boundaries and authority

Ravenroot executes graph-governed workflows while keeping privileged capabilities outside graph authorship.

## Invariants

- A graph author selects node kinds, properties, and routes; the author does not create credentials, install code, or authorize deployment.
- An application supplies an invocation payload and receives execution evidence; it does not become an operator by using the API.
- An integrator binds host services to narrow SPIs; an operator chooses which implementations, identities, networks, and stores are trusted.

## Runtime relationships

- GraphML is declarative intent; the effective catalog is the intersection of that intent and operator-installed capability.
- Test proves traversal without effects; Run may exercise operator-authorized effects.
- The embedded viewer exposes a minimized projection and has neither author nor execution authority.

## Architectural consequence

The application boundary accepts intent while the operator plane supplies authority; neither authorship nor invocation can promote itself across that separation.

## Related reading

- [Exact contract](../security/trust-identity.md)
- [Procedure or recovery](../reference/api-cli.md)
