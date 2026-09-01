# Threat model, identity, and authorization

Ravenroot treats graph documents, payloads, browser input, external services, model output, plugins, and program source as separate trust domains.

## Controls

- Authenticate every reachable control plane; unauthenticated operation is confined to loopback.
- Authorize against both principal role and resource ownership; a valid token is not universal authority.
- Keep operator actions—deployment, drain, adapter installation, global egress, backup, embed registration—outside author and viewer roles.
- Revalidate long-lived access such as SSE and embedded sessions so expiry and revocation take effect.

## Residual responsibility

The residual boundary is explicit: Ravenroot enforces configured identity and ownership but the operator remains responsible for issuer trust, role assignment, host security, and credential backend.

## Application

- [Definitions and limits](../reference/configuration.md)
- [Operator procedure or recovery](../operator-guide/identity-browser.md)
