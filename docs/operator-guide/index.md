# Operator guide

Run Ravenroot as a controlled service with explicit identity, storage, deployment, and embed authority.

## Reading path

- [Deployment and startup](deployment-startup.md) — Select a topology, bind safely, verify health, and expose only the intended service boundary.
- [Identity and browser boundary](identity-browser.md) — Configure local token or OIDC authentication, exact origins, hosts, and session behavior.
- [Credentials, connectors, and egress](credentials-egress.md) — Own secret references, connector installation, tool allowlists, and outbound-network policy.
- [Persistence, lifecycle, and recovery](persistence-lifecycle.md) — Operate readiness, drain, durable state, backup, restore, and controlled upgrades.
- [Embedded-viewer operations](embed-operations.md) — Register a deployment, record seven attestations, issue sessions, audit access, and revoke it.

## Authority boundary

Operator procedures may change service configuration or durable state and therefore include verification and recovery. They never delegate that authority to graph content.
