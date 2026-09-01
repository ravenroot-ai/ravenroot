# Identity and browser boundary

Bind every HTTP, UI, and SSE request to an authenticated principal and an exact browser boundary.

## Operator procedure

1. For controlled loopback use, configure a local token of at least 32 characters; rotate it through the secret channel.
2. For reachable deployments, configure OIDC issuer, audience, and JWKS URI and verify token rejection as well as acceptance.
3. Enumerate exact allowed origins and hosts. Do not use wildcards or split UI and API across unreviewed origins.
4. Confirm that SSE authority is revalidated during a stream and that expiry or revocation closes it.

## Authority

Operators own authentication configuration and global role mapping. Resource ownership still constrains an authenticated caller; identity alone is not universal access.

## Verification

Test an allowed and denied request for REST, browser navigation, and a long-lived SSE connection before exposing traffic.

- [Contract](../security/trust-identity.md)
- [Runbook](../troubleshooting/identity-browser.md)
